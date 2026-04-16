package com.example.bedrock.controller;

import com.example.bedrock.model.SummarizeRequest;
import com.example.bedrock.model.SummarizeResponse;
import com.example.bedrock.service.SummarizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller exposing the Text Summarization API.
 *
 * <h2>Base path</h2>
 * {@code /api/summarize}
 *
 * <h2>Endpoints</h2>
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/summarize</td><td>Summarize text with a chosen style</td></tr>
 *   <tr><td>GET</td><td>/api/summarize/styles</td><td>List all available summary styles</td></tr>
 * </table>
 *
 * <h2>Quick example</h2>
 * <pre>{@code
 * curl -X POST http://localhost:8080/api/summarize \
 *      -H "Content-Type: application/json" \
 *      -d '{
 *            "text": "Amazon Bedrock is a fully managed service...",
 *            "style": "BULLET_POINTS"
 *          }'
 * }</pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/summarize")
@RequiredArgsConstructor
public class SummarizationController {

    private final SummarizationService summarizationService;

    /**
     * Summarizes the provided text using the requested style.
     *
     * <p>Supported styles:
     * <ul>
     *   <li>{@code BRIEF}         — 2–3 sentence overview (default)</li>
     *   <li>{@code DETAILED}      — Multi-paragraph comprehensive summary</li>
     *   <li>{@code BULLET_POINTS} — Key points as a bulleted list</li>
     *   <li>{@code HEADLINE}      — Single one-line headline</li>
     *   <li>{@code EXECUTIVE}     — Structured business executive summary</li>
     * </ul>
     *
     * @param request the summarization request with text and options
     * @return {@code 200 OK} with the summary and compression metrics
     */
    @PostMapping
    public ResponseEntity<SummarizeResponse> summarize(@Valid @RequestBody SummarizeRequest request) {
        log.info("Summarize request — style={}, textLength={}, language={}",
                request.getStyle(), request.getText().length(), request.getLanguage());

        SummarizeResponse response = summarizationService.summarize(request);

        log.info("Summarize complete — originalWords={}, summaryWords={}, ratio={}x",
                response.getOriginalWordCount(),
                response.getSummaryWordCount(),
                response.getCompressionRatio());

        return ResponseEntity.ok(response);
    }

    /**
     * Returns the list of available summarization styles with descriptions.
     * Useful for populating a UI dropdown without hard-coding styles on the client.
     *
     * @return {@code 200 OK} with a JSON array of style descriptors
     */
    @GetMapping("/styles")
    public ResponseEntity<Object> listStyles() {
        var styles = java.util.List.of(
                styleInfo("BRIEF",         "2–3 sentence high-level overview"),
                styleInfo("DETAILED",      "Multi-paragraph summary preserving key details"),
                styleInfo("BULLET_POINTS", "Key points formatted as a bulleted list"),
                styleInfo("HEADLINE",      "Single one-line title / headline"),
                styleInfo("EXECUTIVE",     "Business executive summary with structured takeaways")
        );
        return ResponseEntity.ok(styles);
    }

    private java.util.Map<String, String> styleInfo(String name, String description) {
        return java.util.Map.of("style", name, "description", description);
    }
}
