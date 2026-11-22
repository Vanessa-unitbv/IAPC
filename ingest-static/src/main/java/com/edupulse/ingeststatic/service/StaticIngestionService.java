package com.edupulse.ingeststatic.service;

import com.edupulse.ingeststatic.config.IngestProperties;
import com.edupulse.ragcommon.ChunkMetadata;
import com.edupulse.ragcommon.chunking.JTokkitTokenCounter;
import com.edupulse.ragcommon.chunking.PdfTextExtractor;
import com.edupulse.ragcommon.chunking.StructureAwareSplitter;
import com.edupulse.ragcommon.chunking.TextCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class StaticIngestionService {

    private static final Logger log = LoggerFactory.getLogger(StaticIngestionService.class);
    private static final Pattern LONG_WORD = Pattern.compile("\\p{L}{25,}");
    private static final Pattern URL_OR_EMAIL = Pattern.compile(
            "(?i)^(?:https?://\\S+|www\\.\\S+|[\\p{L}0-9._%+-]+@[\\p{L}0-9.-]+\\.[a-z]{2,}|(?:[\\p{L}0-9-]+\\.)+[a-z]{2,})$");

    private final ChunkWriter chunkWriter;
    private final JdbcTemplate jdbcTemplate;
    private final IngestProperties props;
    private final PdfTextExtractor extractor;
    private final JTokkitTokenCounter tokenCounter = new JTokkitTokenCounter();

    public StaticIngestionService(ChunkWriter chunkWriter, JdbcTemplate jdbcTemplate, IngestProperties props) {
        this.chunkWriter = chunkWriter;
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        this.extractor = new PdfTextExtractor(props.isSortByPosition());
    }

    /** Ingests every PDF in the resources folder. A failing file is reported and does not stop the batch. */
    public Map<String, Object> ingestAllStaticResources() {
        Path dir = Paths.get(props.getResourcesDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Resources directory not found: " + dir);
        }

        List<String> ingested = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();

        List<Path> pdfs;
        try (Stream<Path> paths = Files.list(dir)) {
            pdfs = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan resources directory: " + dir, e);
        }

        for (Path pdf : pdfs) {
            String docId = pdf.getFileName().toString();
            try {
                switch (ingestSinglePdf(pdf, docId)) {
                    case INGESTED -> ingested.add(docId);
                    case UPDATED -> updated.add(docId);
                    case UNCHANGED -> skipped.add(docId);
                }
            } catch (Exception e) {
                log.error("Ingestion failed for {}: {}", docId, e.getMessage(), e);
                failed.add(Map.of("doc_id", docId, "error", String.valueOf(e.getMessage())));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("directory", dir.toString());
        summary.put("embedding_model", props.getEmbeddingModel());
        summary.put("chunk_size_tokens", props.getChunkSize());
        summary.put("chunk_overlap_tokens", props.getChunkOverlap());
        summary.put("pipeline_version", props.getPipelineVersion());
        summary.put("sort_by_position", props.isSortByPosition());
        summary.put("ingested_new", ingested);
        summary.put("updated", updated);
        summary.put("skipped_unchanged", skipped);
        summary.put("failed", failed);
        summary.put("timestamp", Instant.now().toString());
        return summary;
    }

    ChunkWriter.Result ingestSinglePdf(Path pdf, String docId) throws IOException {
        String checksum = sha256(pdf);
        String fingerprint = fingerprint(checksum);

        if (fingerprint.equals(chunkWriter.currentFingerprint(docId))) {
            log.info("Skipping unchanged file: {} (checksum={})", docId, checksum);
            return ChunkWriter.Result.UNCHANGED;
        }

        // parsing and splitting run outside the transaction
        var pages = extractor.extract(pdf);
        var splitter = new StructureAwareSplitter(tokenCounter, props.getChunkSize(), props.getChunkOverlap());
        var chunks = splitter.split(pages);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("No extractable text in " + docId + " (scanned PDF? OCR is not supported)");
        }

        String ingestedAt = Instant.now().toString();
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i);
            if (TextCleaner.garbledCount(c.text()) > 0) {
                log.warn("{} chunk {} (page {}) contains replacement characters; check the PDF fonts", docId, i, c.pageStart());
            }
            Map<String, Object> md = new HashMap<>();
            md.put(ChunkMetadata.DOC_ID, docId);
            md.put(ChunkMetadata.VERSION, 1);
            md.put(ChunkMetadata.SOURCE, docId);
            md.put(ChunkMetadata.SOURCE_TYPE, ChunkMetadata.SOURCE_TYPE_STATIC);
            md.put(ChunkMetadata.PAGE, c.pageStart());
            md.put(ChunkMetadata.PAGE_END, c.pageEnd());
            md.put(ChunkMetadata.CHUNK_INDEX, i);
            md.put(ChunkMetadata.TOTAL_CHUNKS, chunks.size());
            md.put(ChunkMetadata.CHECKSUM, checksum);
            md.put(ChunkMetadata.INGEST_FINGERPRINT, fingerprint);
            md.put(ChunkMetadata.EMBEDDING_MODEL, props.getEmbeddingModel());
            md.put(ChunkMetadata.CHUNK_SIZE, props.getChunkSize());
            md.put(ChunkMetadata.CHUNK_OVERLAP, props.getChunkOverlap());
            md.put(ChunkMetadata.INGESTED_AT, ingestedAt);
            md.put(ChunkMetadata.IS_LATEST, true);
            documents.add(new Document(c.text(), md));
        }

        ChunkWriter.Result result = chunkWriter.replace(docId, fingerprint, documents);
        log.info("{}: {} ({} chunks)", docId, result, documents.size());
        return result;
    }

    /** File checksum combined with everything that changes the produced chunks or embeddings. */
    private String fingerprint(String checksum) {
        String input = checksum + "|" + props.getChunkSize() + "|" + props.getChunkOverlap()
                + "|" + props.getEmbeddingModel() + "|" + props.getPipelineVersion()
                + "|" + props.isSortByPosition();
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    /** Random chunks plus automatic red flags, for the manual "20 random chunks" quality check. */
    public List<Map<String, Object>> sampleChunks(int n) {
        int limit = Math.max(1, Math.min(n, 100));
        return jdbcTemplate.query(
                "SELECT content, metadata::text AS metadata FROM vector_store ORDER BY random() LIMIT ?",
                (rs, i) -> {
                    String content = rs.getString("content");
                    List<String> flags = new ArrayList<>();
                    if (TextCleaner.garbledCount(content) > 0) flags.add("REPLACEMENT_CHAR");
                    if (TextCleaner.hasControlChars(content)) flags.add("CONTROL_CHAR");
                    if (TextCleaner.looksMojibake(content)) flags.add("MOJIBAKE");
                    if (content.length() < 40) flags.add("VERY_SHORT");
                    if (hasVeryLongWord(content)) flags.add("VERY_LONG_WORD");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("metadata", rs.getString("metadata"));
                    row.put("flags", flags);
                    row.put("content", content);
                    return row;
                }, limit);
    }

    static boolean hasVeryLongWord(String content) {
        for (String token : content.split("\\s+")) {
            String candidate = token.replaceAll("^[\\p{P}]+|[\\p{P}]+$", "");
            if (!URL_OR_EMAIL.matcher(candidate).matches() && LONG_WORD.matcher(candidate).find()) {
                return true;
            }
        }
        return false;
    }

    private String sha256(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to compute SHA-256 for " + file, e);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}