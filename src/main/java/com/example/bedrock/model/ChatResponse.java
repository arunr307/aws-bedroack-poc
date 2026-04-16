package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response body returned by the {@code POST /api/chat} endpoint.
 *
 * <pre>{@code
 * {
 *   "reply":   "The capital of France is Paris.",
 *   "modelId": "anthropic.claude-3-5-sonnet-20241022-v2:0",
 *   "usage": {
 *     "inputTokens":  12,
 *     "outputTokens": 9,
 *     "totalTokens":  21
 *   },
 *   "conversationHistory": [ ... ],
 *   "timestamp": "2025-04-16T10:30:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /** The model's reply to the user's message. */
    private String reply;

    /** The Bedrock model ID that generated this reply. */
    private String modelId;

    /** Token usage statistics for this request. */
    private TokenUsage usage;

    /**
     * Full conversation history including the latest user message and
     * assistant reply — ready to pass back as {@code conversationHistory}
     * in the next request for multi-turn conversations.
     */
    private List<ChatMessage> conversationHistory;

    /** Server-side timestamp when the response was produced. */
    private Instant timestamp;

    /**
     * Token usage breakdown returned by Bedrock.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenUsage {
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;
    }
}
