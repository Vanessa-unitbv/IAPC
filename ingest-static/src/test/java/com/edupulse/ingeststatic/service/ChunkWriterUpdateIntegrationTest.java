package com.edupulse.ingeststatic.service;

import com.edupulse.ragcommon.ChunkMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=integration-test-key",
        "eureka.client.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class ChunkWriterUpdateIntegrationTest {

    private static final String OLD_FINGERPRINT = "old-fingerprint";
    private static final String NEW_FINGERPRINT = "new-fingerprint";

    @Autowired
    private ChunkWriter chunkWriter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VectorStore vectorStore;

    private String docId;

    @BeforeEach
    void setUp() {
        docId = "task2-update-test-" + UUID.randomUUID();
        doAnswer(invocation -> {
            List<Document> documents = invocation.getArgument(0);
            for (Document document : documents) {
                insertChunk(document);
            }
            return null;
        }).when(vectorStore).add(anyList());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'doc_id' = ?", docId);
    }

    @Test
    void updateReplacesOldChunksWithTheExpectedChunkCount() throws JsonProcessingException {
        insertOldChunks();
        List<Document> replacement = List.of(
                chunk(0, NEW_FINGERPRINT, "new chunk zero"),
                chunk(1, NEW_FINGERPRINT, "new chunk one"),
                chunk(2, NEW_FINGERPRINT, "new chunk two"));

        ChunkWriter.Result result = chunkWriter.replace(docId, NEW_FINGERPRINT, replacement);

        assertEquals(ChunkWriter.Result.UPDATED, result);
        assertEquals(3, chunkCount());
        assertEquals(List.of("new chunk zero", "new chunk one", "new chunk two"), storedContents());
    }

    @Test
    void unchangedFingerprintSkipsTheWriteAndKeepsTheExistingChunkCount() throws JsonProcessingException {
        insertOldChunks();

        ChunkWriter.Result result = chunkWriter.replace(
                docId,
                OLD_FINGERPRINT,
                List.of(chunk(0, OLD_FINGERPRINT, "should not be stored")));

        assertEquals(ChunkWriter.Result.UNCHANGED, result);
        assertEquals(2, chunkCount());
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void failedInsertRollsBackTheDeleteAndAnyPartialInsert() throws JsonProcessingException {
        insertOldChunks();
        doAnswer(invocation -> {
            List<Document> documents = invocation.getArgument(0);
            insertChunk(documents.get(0));
            throw new IllegalStateException("simulated vector-store failure");
        }).when(vectorStore).add(anyList());

        assertThrows(IllegalStateException.class, () -> chunkWriter.replace(
                docId,
                NEW_FINGERPRINT,
                List.of(
                        chunk(0, NEW_FINGERPRINT, "partial new chunk"),
                        chunk(1, NEW_FINGERPRINT, "second new chunk"))));

        assertEquals(2, chunkCount());
        assertEquals(List.of("old chunk zero", "old chunk one"), storedContents());
    }

    private void insertOldChunks() throws JsonProcessingException {
        insertChunk(chunk(0, OLD_FINGERPRINT, "old chunk zero"));
        insertChunk(chunk(1, OLD_FINGERPRINT, "old chunk one"));
    }

    private Document chunk(int index, String fingerprint, String content) {
        return new Document(content, Map.of(
                ChunkMetadata.DOC_ID, docId,
                ChunkMetadata.VERSION, 1,
                ChunkMetadata.CHUNK_INDEX, index,
                ChunkMetadata.INGEST_FINGERPRINT, fingerprint));
    }

    private void insertChunk(Document document) throws JsonProcessingException {
        jdbcTemplate.update(
                "INSERT INTO vector_store (content, metadata) VALUES (?, CAST(? AS json))",
                document.getText(),
                objectMapper.writeValueAsString(document.getMetadata()));
    }

    private int chunkCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'doc_id' = ?",
                Integer.class,
                docId);
    }

    private List<String> storedContents() {
        return jdbcTemplate.queryForList(
                "SELECT content FROM vector_store WHERE metadata->>'doc_id' = ? "
                        + "ORDER BY (metadata->>'chunk_index')::int",
                String.class,
                docId);
    }
}
