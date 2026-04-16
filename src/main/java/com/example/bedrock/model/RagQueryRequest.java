package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/rag/query}.
 *
 * <h2>Minimal</h2>
 * <pre>{@code { "question": "What is AWS Lambda?" }}</pre>
 *
 * <h2>Full control</h2>
 * <pre>{@code
 * {
 *   "question": "What is AWS Lambda and how does pricing work?",
 *   "topK": 5,
 *   "minScore": 0.65,
 *   "modelId": "amazon.nova-pro-v1:0",
 *   "systemPrompt": "You are an AWS Solutions Architect. Be concise and technical."
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagQueryRequest {

    /** The question to answer using the knowledge base. */
    @NotBlank(message = "question must not be blank")
    @Size(max = 10_000, message = "question must not exceed 10,000 characters")
    private String question;

    /**
     * Maximum number of chunks to retrieve from the store and inject as context.
     * More chunks = richer context but higher token cost. Default: {@code 5}.
     */
    @Builder.Default
    @Positive(message = "topK must be positive")
    private int topK = 5;

    /**
     * Minimum cosine similarity score for a chunk to be included as context.
     * Raise to get only highly relevant chunks; lower to cast a wider net.
     * Default: {@code 0.5}.
     */
    @Builder.Default
    private double minScore = 0.5;

    /**
     * Generation model ID override. Defaults to {@code aws.bedrock.model-id} in
     * {@code application.yml}. Note: this is the *chat* model, not the embedding model.
     */
    private String modelId;

    /**
     * Optional system prompt override. When not set, a default grounding prompt is used:
     * <em>"Answer the question using only the provided context. If the answer is not in
     * the context, say you don't know."</em>
     */
    private String systemPrompt;
}
