package com.edupulse.ingeststatic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Ingestion settings (prefix {@code app.ingest}). Chunk size and overlap are measured in TOKENS (cl100k_base).
 */
@Component
@ConfigurationProperties(prefix = "app.ingest")
public class IngestProperties {

    /** Folder with the PDFs to ingest. */
    private String resourcesDir = "./resources";

    /** Maximum chunk size, in tokens. */
    private int chunkSize = 800;

    /** Tokens repeated from the end of one chunk at the start of the next. Must be < chunkSize. */
    private int chunkOverlap = 100;

    /**
     * Name of the embedding model, stored in metadata and in the ingestion fingerprint.
     * Bound in application.yaml to {@code spring.ai.openai.embedding.options.model}, so it always matches
     * the model Spring AI uses. The default below only applies when no configuration file is loaded.
     */
    private String embeddingModel = "text-embedding-3-small";

    /** Bump when the extraction/splitting logic changes, to force re-ingestion of existing files. */
    private int pipelineVersion = 3;

    public String getResourcesDir() { return resourcesDir; }
    public void setResourcesDir(String resourcesDir) { this.resourcesDir = resourcesDir; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getPipelineVersion() { return pipelineVersion; }
    public void setPipelineVersion(int pipelineVersion) { this.pipelineVersion = pipelineVersion; }
}
