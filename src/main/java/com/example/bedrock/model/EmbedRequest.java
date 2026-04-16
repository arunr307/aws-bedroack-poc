package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/embeddings/embed}.
 *
 * <h2>Minimal</h2>
 * <pre>{@code { "text": "AWS Lambda runs code without provisioning servers." }}</pre>
 *
 * <h2>With overrides</h2>
 * <pre>{@code
 * {
 *   "text": "AWS Lambda runs code without provisioning servers.",
 *   "dimensions": 512,
 *   "normalize": false,
 *   "modelId": "amazon.titan-embed-text-v2:0"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbedRequest {

    /** Text to convert into a vector embedding. Max ~8 000 tokens for Titan V2. */
    @NotBlank(message = "text must not be blank")
    @Size(max = 50_000, message = "text must not exceed 50,000 characters")
    private String text;

    /**
     * Embedding vector dimensions. Supported values for Titan Embed Text V2:
     * {@code 256}, {@code 512}, {@code 1024} (default from config).
     * Smaller dimensions are faster and cheaper; larger capture more semantic nuance.
     */
    private Integer dimensions;

    /**
     * Whether to L2-normalise the output vector.
     * When {@code true} (default), cosine similarity equals the plain dot product —
     * no extra division needed. Leave {@code null} to use the config default.
     */
    private Boolean normalize;

    /**
     * Optional embedding model ID override.
     * Defaults to {@code aws.bedrock.embedding.model-id} in {@code application.yml}.
     */
    private String modelId;
}
