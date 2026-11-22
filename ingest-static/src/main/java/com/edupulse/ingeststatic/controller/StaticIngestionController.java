package com.edupulse.ingeststatic.controller;

import com.edupulse.ingeststatic.service.StaticIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/ingest/static", produces = StaticIngestionController.JSON_UTF8)
public class StaticIngestionController {

    /** Explicit charset: Windows PowerShell 5.1 decodes JSON without a charset as Latin-1 and garbles diacritics. */
    static final String JSON_UTF8 = "application/json;charset=UTF-8";

    private final StaticIngestionService ingestionService;

    public StaticIngestionController(StaticIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runIngestion() {
        return ResponseEntity.ok(ingestionService.ingestAllStaticResources());
    }

    /** Random chunks for the manual quality check (default 20, max 100). */
    @GetMapping("/sample")
    public ResponseEntity<List<Map<String, Object>>> sample(@RequestParam(defaultValue = "20") int n) {
        return ResponseEntity.ok(ingestionService.sampleChunks(n));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> status() {
        return ResponseEntity.ok(Map.of("service", "ingest-static", "status", "READY"));
    }
}
