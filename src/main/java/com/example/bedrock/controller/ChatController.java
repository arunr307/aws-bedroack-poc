package com.example.bedrock.controller;

import com.example.bedrock.model.ChatRequest;
import com.example.bedrock.model.ChatResponse;
import com.example.bedrock.service.ChatService;
import com.example.bedrock.service.StreamingChatService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;

/**
 * REST controller exposing the AWS Bedrock Chat API.
 *
 * <h2>Base path</h2>
 * {@code /api/chat}
 *
 * <h2>Endpoints</h2>
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/chat</td><td>Blocking chat — full reply in one JSON response</td></tr>
 *   <tr><td>POST</td><td>/api/chat/stream</td><td>Streaming chat — tokens via Server-Sent Events</td></tr>
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
public class ChatController {

    private final ChatService          chatService;
    private final StreamingChatService streamingChatService;
    private final Executor             streamingExecutor;

    public ChatController(
            ChatService chatService,
            StreamingChatService streamingChatService,
            @Qualifier("streamingExecutor") Executor streamingExecutor) {
        this.chatService          = chatService;
        this.streamingChatService = streamingChatService;
        this.streamingExecutor    = streamingExecutor;
    }

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
     * Stream a chat response token-by-token using Server-Sent Events (SSE).
     *
     * <p>The HTTP connection stays open while the model generates its reply.
     * Each token is pushed as a {@code data:} SSE event as soon as it arrives
     * from Bedrock, giving the user a real-time "typing" experience.
     *
     * <h2>Event format</h2>
     * <pre>{@code
     * data: {"token":"Hello","done":false}
     * data: {"token":", how can I help?","done":false}
     * data: {"token":"","done":true,"modelId":"amazon.nova-lite-v1:0",
     *         "usage":{"inputTokens":10,"outputTokens":7,"totalTokens":17}}
     * }</pre>
     *
     * <h2>Example</h2>
     * <pre>{@code
     * curl -N -X POST http://localhost:8080/api/chat/stream \
     *      -H "Content-Type: application/json" \
     *      -d '{ "message": "Explain AWS Lambda in simple terms." }'
     * }</pre>
     *
     * @param request the chat request (same schema as {@code POST /api/chat})
     * @return an SSE stream of {@link com.example.bedrock.model.StreamToken} events
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request) {
        log.info("Streaming chat request received — messageLength={}", request.getMessage().length());

        // 60-second timeout — increase for very long responses
        SseEmitter emitter = new SseEmitter(60_000L);

        streamingExecutor.execute(() -> {
            try {
                streamingChatService.stream(request, emitter);
            } catch (Exception ex) {
                log.error("Unexpected error during streaming", ex);
                emitter.completeWithError(ex);
            }
        });

        return emitter;
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
