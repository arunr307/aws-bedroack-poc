package com.example.bedrock.controller;

import com.example.bedrock.model.AgentRequest;
import com.example.bedrock.model.AgentResponse;
import com.example.bedrock.service.AgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller exposing the Agent API with tool/function calling.
 *
 * <h2>Base path</h2>
 * {@code /api/agent}
 *
 * <h2>Endpoints</h2>
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/agent/chat</td><td>Run an agentic loop with tool calling</td></tr>
 *   <tr><td>GET</td><td>/api/agent/tools</td><td>List available built-in tools</td></tr>
 *   <tr><td>GET</td><td>/api/agent/health</td><td>Service health check</td></tr>
 * </table>
 *
 * <h2>Example — let the agent use tools automatically</h2>
 * <pre>{@code
 * curl -X POST http://localhost:8080/api/agent/chat \
 *      -H "Content-Type: application/json" \
 *      -d '{ "message": "What is 1234 multiplied by 5678?" }'
 * }</pre>
 *
 * <h2>Example — restrict to specific tools</h2>
 * <pre>{@code
 * curl -X POST http://localhost:8080/api/agent/chat \
 *      -H "Content-Type: application/json" \
 *      -d '{
 *            "message": "Convert 100 pounds to kilograms",
 *            "enabledTools": ["unit_converter"]
 *          }'
 * }</pre>
 *
 * <h2>Example — check what tools were used</h2>
 * <p>The response includes a {@code toolCalls} array listing each tool the agent
 * invoked, its inputs, and its output — useful for debugging and transparency.
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /**
     * Run an agentic loop: the model may call one or more built-in tools before
     * producing its final answer.
     *
     * @param request agent request with user message and optional configuration
     * @return final answer plus a log of all tool invocations
     */
    @PostMapping("/chat")
    public ResponseEntity<AgentResponse> chat(@Valid @RequestBody AgentRequest request) {
        log.info("Agent chat request — messageLength={}, enabledTools={}",
                request.getMessage().length(),
                request.getEnabledTools());

        AgentResponse response = agentService.runAgent(request);

        log.info("Agent chat complete — iterations={}, toolCalls={}, inputTokens={}, outputTokens={}",
                response.getIterations(),
                response.getToolCalls().size(),
                response.getUsage().getInputTokens(),
                response.getUsage().getOutputTokens());

        return ResponseEntity.ok(response);
    }

    /**
     * List all built-in tools available to the agent.
     *
     * @return map of tool name → description
     */
    @GetMapping("/tools")
    public ResponseEntity<Map<String, String>> listTools() {
        return ResponseEntity.ok(Map.of(
                "calculator",
                "Arithmetic and math operations: add, subtract, multiply, divide, power, sqrt, modulo",
                "get_current_time",
                "Get the current date/time, optionally in a specific timezone and format",
                "string_utils",
                "Text transformations: uppercase, lowercase, reverse, word_count, char_count, trim",
                "unit_converter",
                "Unit conversions: temperature (°C/°F/K), length (m/ft/in/km/mi), weight (kg/lb/g/oz)"
        ));
    }

    /**
     * Health-check endpoint for the agent service.
     *
     * @return {@code 200 OK} with a status message
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Agent service is running");
    }
}
