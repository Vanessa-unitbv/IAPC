package com.edupulse.ingestdynamic.config;

import com.edupulse.ingestdynamic.ingest.SourceSpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Settings of the dynamic ingestion (prefix {@code app.ingest}); documented in application.yaml and .env.example. */
@Component
@ConfigurationProperties(prefix = "app.ingest")
public class DynamicIngestProperties {

    /** Folder watched for local files (Story 2, Task 3). */
    private String resourcesDir = "./resources";

    /** Domains that may be fetched (and their subdomains). Empty means nothing can be fetched. */
    private List<String> allowedDomains = new ArrayList<>();

    /** Maximum size of one downloaded source. */
    private int maxSizeMb = 20;

    /** Connect and read timeout for one request. */
    private int timeoutSeconds = 20;

    /** Redirects followed per source; each target is checked against the allowlist again. */
    private int maxRedirects = 3;

    /** Chunk size and overlap, in tokens (cl100k_base). */
    private int chunkSize = 800;
    private int chunkOverlap = 100;

    /** Embedding model, stored in metadata and in the ingestion fingerprint. Bound to Spring AI's model. */
    private String embeddingModel = "text-embedding-3-small";

    /** Bump when the extraction or splitting logic changes, to force re-ingestion of unchanged sources. */
    private int pipelineVersion = 2;

    /** Sources ingested by POST /ingest/dynamic/run when the request has no body. */
    private List<SourceSpec> sources = new ArrayList<>();

    public String getResourcesDir() { return resourcesDir; }
    public void setResourcesDir(String resourcesDir) { this.resourcesDir = resourcesDir; }
    public List<String> getAllowedDomains() { return allowedDomains; }
    public void setAllowedDomains(List<String> allowedDomains) { this.allowedDomains = allowedDomains; }
    public int getMaxSizeMb() { return maxSizeMb; }
    public void setMaxSizeMb(int maxSizeMb) { this.maxSizeMb = maxSizeMb; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMaxRedirects() { return maxRedirects; }
    public void setMaxRedirects(int maxRedirects) { this.maxRedirects = maxRedirects; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getPipelineVersion() { return pipelineVersion; }
    public void setPipelineVersion(int pipelineVersion) { this.pipelineVersion = pipelineVersion; }
    public List<SourceSpec> getSources() { return sources; }
    public void setSources(List<SourceSpec> sources) { this.sources = sources; }
}
