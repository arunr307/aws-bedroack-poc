package com.example.bedrock.controller;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.model.FlowInvokeRequest;
import com.example.bedrock.model.FlowInvokeResponse;
import com.example.bedrock.service.PromptFlowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Amazon Bedrock Prompt Flows.
 *
 * <h2>Base path</h2>
 * {@code /api/flows}
 *
 * <h2>Endpoints</h2>
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/flows/invoke</td>
 *       <td>Invoke a flow with an input document</td></tr>
 *   <tr><td>GET</td><td>/api/flows</td>
 *       <td>List all Prompt Flows in the account</td></tr>
 *   <tr><td>GET</td><td>/api/flows/{flowId}</td>
 *       <td>Get metadata for a specific flow</td></tr>
 *   <tr><td>GET</td><td>/api/flows/{flowId}/aliases</td>
 *       <td>List aliases for a specific flow</td></tr>
 *   <tr><td>GET</td><td>/api/flows/health</td>
 *       <td>Health check with configured flow IDs</td></tr>
 * </table>
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * # 1. Check which flow is configured
 * curl http://localhost:8080/api/flows/health
 *
 * # 2. List available flows in your account
 * curl http://localhost:8080/api/flows
 *
 * # 3. Invoke the configured default flow
 * curl -X POST http://localhost:8080/api/flows/invoke \
 *      -H "Content-Type: application/json" \
 *      -d '{ "input": "What are the key benefits of serverless architecture?" }'
 * }</pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/flows")
@RequiredArgsConstructor
public class PromptFlowController {

    private final PromptFlowService  promptFlowService;
    private final BedrockProperties  properties;

    // ── Invoke ────────────────────────────────────────────────────────────────

    /**
     * Invokes a Bedrock Prompt Flow with the supplied input text.
     *
     * <p>Uses the {@code FLOW_ID} / {@code FLOW_ALIAS_ID} configured in
     * {@code launch.json} when not supplied in the request body.
     *
     * @param request input text, optional flow/alias ID overrides, optional trace flag
     * @return outputs from every Output node, completion reason, and optional trace
     */
    @PostMapping("/invoke")
    public ResponseEntity<FlowInvokeResponse> invoke(
            @Valid @RequestBody FlowInvokeRequest request) {

        log.info("POST /api/flows/invoke — flowId={}, aliasId={}, inputLength={}, trace={}",
                request.getFlowId(), request.getFlowAliasId(),
                request.getInput().length(), request.isEnableTrace());

        FlowInvokeResponse response = promptFlowService.invoke(request);

        log.info("Flow invoke complete — outputs={}, reason={}",
                response.getOutputs().size(), response.getCompletionReason());

        return ResponseEntity.ok(response);
    }

    // ── Management (list / get) ───────────────────────────────────────────────

    /**
     * Lists all Prompt Flows in the account and region.
     *
     * <p>Use this to discover Flow IDs without going to the AWS Console.
     *
     * @return list of flow summaries (id, name, status, description)
     */
    @GetMapping
    public ResponseEntity<List<Map<String, String>>> listFlows() {
        log.info("GET /api/flows — listing all flows");
        return ResponseEntity.ok(promptFlowService.listFlows());
    }

    /**
     * Returns metadata for a specific flow.
     *
     * @param flowId the Flow ID
     * @return flow metadata (id, name, status, description, timestamps)
     */
    @GetMapping("/{flowId}")
    public ResponseEntity<Map<String, Object>> getFlow(@PathVariable String flowId) {
        log.info("GET /api/flows/{} — fetching flow metadata", flowId);
        return ResponseEntity.ok(promptFlowService.getFlow(flowId));
    }

    /**
     * Lists all aliases for a specific flow.
     *
     * <p>Use this to find the Alias ID to pass in invoke requests.
     * The draft alias always has ID {@code TSTALIASID}.
     *
     * @param flowId the Flow ID
     * @return list of alias summaries (id, name, description, timestamps)
     */
    @GetMapping("/{flowId}/aliases")
    public ResponseEntity<List<Map<String, String>>> listAliases(@PathVariable String flowId) {
        log.info("GET /api/flows/{}/aliases — listing aliases", flowId);
        return ResponseEntity.ok(promptFlowService.listAliases(flowId));
    }

    // ── Health ────────────────────────────────────────────────────────────────

    /**
     * Returns the Prompt Flow configuration status.
     * Use this to verify {@code FLOW_ID} and {@code FLOW_ALIAS_ID} are set.
     *
     * @return configuration status
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        BedrockProperties.Flow cfg = properties.getBedrock().getFlow();

        boolean flowConfigured  = cfg.getDefaultFlowId()  != null && !cfg.getDefaultFlowId().isBlank();
        boolean aliasConfigured = cfg.getDefaultAliasId() != null && !cfg.getDefaultAliasId().isBlank();
        boolean fullyConfigured = flowConfigured && aliasConfigured;

        return ResponseEntity.ok(Map.of(
                "status",           fullyConfigured ? "UP" : "UNCONFIGURED",
                "service",          "prompt-flows",
                "defaultFlowId",    flowConfigured
                        ? cfg.getDefaultFlowId()
                        : "(not set — add FLOW_ID to launch.json)",
                "defaultAliasId",   aliasConfigured
                        ? cfg.getDefaultAliasId()
                        : "(not set — add FLOW_ALIAS_ID to launch.json)",
                "hint",             fullyConfigured
                        ? "Ready — POST /api/flows/invoke with your input"
                        : "Create a flow in AWS Console → Bedrock → Flows, then set FLOW_ID and FLOW_ALIAS_ID"
        ));
    }
}
