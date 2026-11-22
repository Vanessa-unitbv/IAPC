package com.edupulse.ingestdynamic.web;

import com.edupulse.ingestdynamic.config.DynamicIngestProperties;
import com.edupulse.ingestdynamic.ingest.DynamicIngestionService;
import com.edupulse.ingestdynamic.ingest.IngestionReport;
import com.edupulse.ingestdynamic.ingest.SourceSpec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
