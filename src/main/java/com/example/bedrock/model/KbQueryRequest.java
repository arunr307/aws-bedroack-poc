package com.example.bedrock.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/kb/query}.
 *
 * <p>Calls the Bedrock {@code RetrieveAndGenerate} API — retrieves relevant chunks
 * from the managed Knowledge Base and generates a grounded answer in one call.
 *
 * <h2>Minimal</h2>
 * <pre>{@code { "question": "What is AWS Lambda?" } }</pre>
 *
 * <h2>With session (multi-turn follow-up)</h2>
 * <pre>{@code
 * {
 *   "question": "How does it compare to ECS?",
 *   "sessionId": "sess-abc123"
 * }
 * }</pre>
 *
 * <h2>Full control</h2>
 * <pre>{@code
 * {
 *   "question": "What are the pricing tiers?",
 *   "topK": 8,
 *   "modelId": "amazon.nova-pro-v1:0",
 *   "knowledgeBaseId": "ABCDEF1234"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbQueryRequest {

    /** The question to answer using the managed Knowledge Base. */
    @NotBlank(message = "question must not be blank")
    @Size(max = 10_000, message = "question must not exceed 10,000 characters")
    private String question;

    /**
     * Maximum number of chunks to retrieve as context (1–100).
     * Defaults to {@code aws.bedrock.knowledge-base.default-top-k} (5).
     * Higher values give the model richer context at the cost of more input tokens.
     */
    @Min(value = 1,   message = "topK must be at least 1")
    @Max(value = 100, message = "topK must not exceed 100")
    @Builder.Default
    private int topK = 0;   // 0 = use config default

    /**
     * Session ID returned by a previous {@code /api/kb/query} call.
     * Supply it to continue a multi-turn conversation in the same Knowledge Base session —
     * Bedrock maintains conversation history server-side for the session lifetime.
     * Omit (or set {@code null}) to start a fresh session.
     */
    private String sessionId;

    /**
     * Override the Knowledge Base ID for this request.
     * When {@code null}, uses {@code aws.bedrock.knowledge-base.id} from config.
     */
    private String knowledgeBaseId;

    /**
     * Override the generation model for this request.
     * When {@code null}, uses {@code aws.bedrock.knowledge-base.model-id} from config.
     */
    private String modelId;
}
