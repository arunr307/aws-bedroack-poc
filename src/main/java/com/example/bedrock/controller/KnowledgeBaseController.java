package com.example.bedrock.controller;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.model.KbQueryRequest;
import com.example.bedrock.model.KbQueryResponse;
import com.example.bedrock.model.KbRetrieveRequest;
import com.example.bedrock.model.KbRetrieveResponse;
import com.example.bedrock.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for Amazon Bedrock managed Knowledge Bases.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/kb/query}    — retrieve + generate a grounded answer (RetrieveAndGenerate)</li>
 *   <li>{@code POST /api/kb/retrieve} — retrieve relevant chunks only, no generation (Retrieve)</li>
 *   <li>{@code GET  /api/kb/health}   — health check with Knowledge Base configuration status</li>
 * </ul>
 *
 * <h2>Prerequisites</h2>
 * <p>A Knowledge Base must be created in AWS Console and its ID set via the
 * {@code KB_ID} environment variable (or {@code aws.bedrock.knowledge-base.id}
 * in {@code application.yml}).
 */
@Slf4j
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService  knowledgeBaseService;
    private final BedrockProperties     properties;

    // ── RetrieveAndGenerate ───────────────────────────────────────────────────

    /**
     * Retrieves relevant chunks from the managed Knowledge Base and generates a
     * grounded answer in a single API call.
     *
     * <p>Supports multi-turn conversations: include the {@code sessionId} from the
     * previous response to continue the same session. Bedrock maintains history
     * server-side (~1 hour TTL).
     *
     * <h3>Single question</h3>
     * <pre>{@code { "question": "What is AWS Lambda?" } }</pre>
     *
     * <h3>Follow-up in same session</h3>
     * <pre>{@code
     * {
     *   "question": "How does it compare to AWS Fargate?",
     *   "sessionId": "sess-abc123"
     * }
     * }</pre>
     *
     * <h3>With retrieval tuning</h3>
     * <pre>{@code
     * {
     *   "question": "What are the Lambda pricing tiers?",
     *   "topK": 8,
     *   "modelId": "amazon.nova-pro-v1:0"
     * }
     * }</pre>
     *
     * @param request question, optional session, topK, model overrides
     * @return grounded answer, session ID for follow-ups, and source citations
     */
    @PostMapping("/query")
    public ResponseEntity<KbQueryResponse> query(@Valid @RequestBody KbQueryRequest request) {
        log.info("POST /api/kb/query — question='{}'", request.getQuestion());
        return ResponseEntity.ok(knowledgeBaseService.query(request));
    }

    // ── Retrieve-only ─────────────────────────────────────────────────────────

    /**
     * Retrieves relevant chunks from the Knowledge Base <em>without</em> generating
     * an answer. Returns raw chunks with relevance scores.
     *
     * <p>Use this to:
     * <ul>
     *   <li>Inspect what the Knowledge Base contains</li>
     *   <li>Perform custom re-ranking before generation</li>
     *   <li>Feed chunks into a custom prompt ({@code POST /api/chat})</li>
     * </ul>
     *
     * <pre>{@code
     * {
     *   "query": "Lambda cold start mitigation",
     *   "topK": 10
     * }
     * }</pre>
     *
     * @param request search query and optional topK override
     * @return ranked list of document chunks with source URIs and scores
     */
    @PostMapping("/retrieve")
    public ResponseEntity<KbRetrieveResponse> retrieve(@Valid @RequestBody KbRetrieveRequest request) {
        log.info("POST /api/kb/retrieve — query='{}'", request.getQuery());
        return ResponseEntity.ok(knowledgeBaseService.retrieve(request));
    }

    // ── Health ────────────────────────────────────────────────────────────────

    /**
     * Returns the Knowledge Base configuration status.
     * Use this to verify the {@code KB_ID} is correctly configured before querying.
     *
     * @return config status including Knowledge Base ID and generation model
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        BedrockProperties.KnowledgeBase kbConfig =
                properties.getBedrock().getKnowledgeBase();
        boolean configured = kbConfig.getId() != null && !kbConfig.getId().isBlank();

        return ResponseEntity.ok(Map.of(
                "status",           configured ? "UP" : "UNCONFIGURED",
                "service",          "knowledge-base",
                "knowledgeBaseId",  configured ? kbConfig.getId() : "(not set — add KB_ID to launch.json)",
                "modelId",          kbConfig.getModelId(),
                "defaultTopK",      kbConfig.getDefaultTopK()));
    }
}
