package com.example.bedrock.controller;

import com.example.bedrock.model.ChatRequest;
import com.example.bedrock.model.ChatResponse;
import com.example.bedrock.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller exposing the AWS Bedrock Chat API.
 *
 * <h2>Base path</h2>
 * {@code /api/chat}
 *
 * <h2>Endpoints</h2>
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/chat</td><td>Send a message (stateless or multi-turn)</td></tr>
 *   <tr><td>GET</td><td>/api/chat/health</td><td>Simple health-check for the chat service</td></tr>
 * </table>
 *
 * <h2>Example — stateless request</h2>
 * <pre>{@code
 * curl -X POST http://localhost:8080/api/chat \
 *      -H "Content-Type: application/json" \
 *      -d '{ "message": "What is AWS Bedrock?" }'
 * }</pre>
 *
 * <h2>Example — multi-turn request</h2>
 * <pre>{@code
 * curl -X POST http://localhost:8080/api/chat \
 *      -H "Content-Type: application/json" \
 *      -d '{
 *            "message": "And what models does it support?",
 *            "conversationHistory": [
 *              { "role": "user",      "content": "What is AWS Bedrock?" },
 *              { "role": "assistant", "content": "AWS Bedrock is a fully managed ..." }
 *            ]
 *          }'
 * }</pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * Send a message to Amazon Bedrock and receive the model's reply.
     *
     * <p>Supports both stateless (single-turn) and stateful (multi-turn)
     * conversation modes depending on whether {@code conversationHistory}
     * is included in the request body.
     *
     * @param request the chat request containing the user message and optional context
     * @return {@code 200 OK} with the model's reply and updated conversation history
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Chat request received — messageLength={}, historyTurns={}",
                request.getMessage().length(),
                request.getConversationHistory() == null ? 0
                        : request.getConversationHistory().size() / 2);

        ChatResponse response = chatService.chat(request);

        log.info("Chat response sent — modelId={}, inputTokens={}, outputTokens={}",
                response.getModelId(),
                response.getUsage().getInputTokens(),
                response.getUsage().getOutputTokens());

        return ResponseEntity.ok(response);
    }

    /**
     * Health-check endpoint for the chat service.
     *
     * @return {@code 200 OK} with a simple status message
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Chat service is running");
    }
}
