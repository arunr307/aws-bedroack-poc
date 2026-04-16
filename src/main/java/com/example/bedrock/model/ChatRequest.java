package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for the {@code POST /api/chat} endpoint.
 *
 * <h2>Stateless mode (simple)</h2>
 * <pre>{@code
 * {
 *   "message": "What is the capital of France?"
 * }
 * }</pre>
 *
 * <h2>Stateful / multi-turn mode</h2>
 * <p>Include previous messages to give the model conversation context:
 * <pre>{@code
 * {
 *   "message": "What was the first thing I asked?",
 *   "conversationHistory": [
 *     { "role": "user",      "content": "What is the capital of France?" },
 *     { "role": "assistant", "content": "The capital of France is Paris." }
 *   ]
 * }
 * }</pre>
 *
 * <h2>Override model (optional)</h2>
 * <pre>{@code
 * {
 *   "message": "Explain quantum computing briefly.",
 *   "modelId": "anthropic.claude-3-haiku-20240307-v1:0"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /**
     * The user's current message.  Must not be blank.
     */
    @NotBlank(message = "message must not be blank")
    @Size(max = 100_000, message = "message must not exceed 100,000 characters")
    private String message;

    /**
     * Optional prior conversation turns (oldest first).
     * When provided, the model uses them as context for its reply.
     */
    private List<ChatMessage> conversationHistory;

    /**
     * Optional Bedrock model ID to use for this request only.
     * Overrides the {@code aws.bedrock.model-id} setting in {@code application.yml}.
     */
    private String modelId;

    /**
     * Optional system prompt that sets the model's persona / behaviour.
     * Example: {@code "You are a helpful cloud-infrastructure assistant."}
     */
    private String systemPrompt;
}
