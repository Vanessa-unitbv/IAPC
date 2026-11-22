package com.edupulse.ingestdynamic.web;

import com.edupulse.ingestdynamic.config.DynamicIngestProperties;
import com.edupulse.ingestdynamic.ingest.ChunkHit;
import com.edupulse.ingestdynamic.ingest.DynamicIngestionService;
import com.edupulse.ingestdynamic.ingest.IngestionReport;
import com.edupulse.ingestdynamic.ingest.SourceSpec;
import com.edupulse.ragcommon.ChunkMetadata;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/ingest/dynamic", produces = DynamicIngestionController.JSON_UTF8)
public class DynamicIngestionController {

    /** Explicit charset: Windows PowerShell 5.1 decodes JSON without a charset as Latin-1 and garbles diacritics. */
    static final String JSON_UTF8 = "application/json;charset=UTF-8";

    private final DynamicIngestionService ingestionService;
    private final DynamicIngestProperties props;

    public DynamicIngestionController(DynamicIngestionService ingestionService, DynamicIngestProperties props) {
        this.ingestionService = ingestionService;
        this.props = props;
    }

    /**
     * Ingests the sources in the body, e.g. {@code [{"url": "https://..."}, {"repo": "owner/name", "ref": "main",
     * "paths": ["README.md"]}]}, or the configured {@code app.ingest.sources} when there is no body.
     */
    @PostMapping("/run")
    public IngestionReport run(@RequestBody(required = false) List<SourceSpec> sources) {
        List<SourceSpec> toIngest = sources == null || sources.isEmpty() ? props.getSources() : sources;
        if (toIngest == null || toIngest.isEmpty()) {
            throw new IllegalArgumentException(
                    "No sources: send a JSON list of {\"url\": ...} or {\"repo\": ..., \"ref\": ..., \"paths\": [...]}, "
                            + "or configure app.ingest.sources");
        }
        return ingestionService.ingest(toIngest);
    }

    /**
     * Searches the stored chunks: by default only the latest version of each document; with {@code version}
     * (and optionally {@code doc_id}) exactly that version. Example:
     * {@code /ingest/dynamic/search?q=deposit+guarantee&doc_id=https://...&version=1}
     */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam("q") String query,
                                            @RequestParam(value = "k", defaultValue = "5") int topK,
                                            @RequestParam(value = "doc_id", required = false) String docId,
                                            @RequestParam(value = "version", required = false) Integer version) {
        return ingestionService.search(query, topK, docId, version).stream().map(DynamicIngestionController::toJson).toList();
    }

    private static Map<String, Object> toJson(ChunkHit hit) {
        Map<String, Object> md = hit.metadata();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("doc_id", md.get(ChunkMetadata.DOC_ID));
        json.put("version", md.get(ChunkMetadata.VERSION));
        json.put("is_latest", md.get(ChunkMetadata.IS_LATEST));
        json.put("valid_from", md.get(ChunkMetadata.VALID_FROM));
        json.put("valid_to", md.get(ChunkMetadata.VALID_TO));
        json.put("chunk_index", md.get(ChunkMetadata.CHUNK_INDEX));
        json.put("score", hit.score());
        json.put("text", hit.text());
        return json;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("service", "ingest-dynamic");
        status.put("status", "READY");
        status.put("allowed_domains", props.getAllowedDomains());
        status.put("configured_sources", props.getSources().size());
        return status;
    }
}
