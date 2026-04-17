package com.example.bedrock.controller;

import com.example.bedrock.model.AnalysisRequest;
import com.example.bedrock.model.AnalysisResponse;
import com.example.bedrock.model.AnalysisType;
import com.example.bedrock.service.DocumentAnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * REST controller for document analysis using Amazon Bedrock.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/analysis/analyze} — analyse text (sentiment, entities, key phrases,
 *       classification, language)</li>
 *   <li>{@code GET  /api/analysis/types}   — list available analysis types</li>
 *   <li>{@code GET  /api/analysis/health}  — health check</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class DocumentAnalysisController {

    private final DocumentAnalysisService analysisService;

    // ── Analyse ───────────────────────────────────────────────────────────────

    /**
     * Analyses a document and returns the requested insights in a single call.
     *
     * <p>All five analyses run by default when {@code analysisTypes} is omitted.
     * Selectively specify types to reduce latency and token cost.
     *
     * <h3>Minimal — run all analyses</h3>
     * <pre>{@code
     * {
     *   "text": "Apple Inc. reported record revenue of $89.5B in Q1 2024, beating analyst expectations..."
     * }
     * }</pre>
     *
     * <h3>Selective — sentiment + entities only</h3>
     * <pre>{@code
     * {
     *   "text": "...",
     *   "analysisTypes": ["SENTIMENT", "ENTITIES"]
     * }
     * }</pre>
     *
     * <h3>Custom classification labels</h3>
     * <pre>{@code
     * {
     *   "text": "...",
     *   "analysisTypes": ["CLASSIFICATION"],
     *   "customLabels": ["Bug Report", "Feature Request", "General Inquiry", "Billing"]
     * }
     * }</pre>
     *
     * @param request text and optional analysis parameters
     * @return typed analysis results
     */
    @PostMapping("/analyze")
    public ResponseEntity<AnalysisResponse> analyze(@Valid @RequestBody AnalysisRequest request) {
        log.info("POST /api/analysis/analyze — textLength={}, types={}",
                request.getText().length(),
                request.getAnalysisTypes() == null ? "ALL" : request.getAnalysisTypes());
        AnalysisResponse response = analysisService.analyze(request);
        return ResponseEntity.ok(response);
    }

    // ── Types catalogue ───────────────────────────────────────────────────────

    /**
     * Returns all available analysis types with descriptions.
     * Useful for populating a UI picker or exploring the API.
     *
     * @return list of {@code {type, description}} objects
     */
    @GetMapping("/types")
    public ResponseEntity<List<Map<String, String>>> listTypes() {
        List<Map<String, String>> types = Arrays.stream(AnalysisType.values())
                .map(t -> Map.of(
                        "type",        t.name(),
                        "description", t.getDescription()))
                .toList();
        return ResponseEntity.ok(types);
    }

    // ── Health ────────────────────────────────────────────────────────────────

    /**
     * Simple liveness check.
     *
     * @return {@code {"status":"UP","service":"document-analysis"}}
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status",  "UP",
                "service", "document-analysis"));
    }
}
