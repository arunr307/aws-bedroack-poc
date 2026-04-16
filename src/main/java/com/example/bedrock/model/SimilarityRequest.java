package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/embeddings/similarity}.
 *
 * <p>Embeds both texts and computes their cosine similarity score in a single call.
 *
 * <pre>{@code
 * {
 *   "textA": "AWS Lambda is a serverless compute service.",
 *   "textB": "Lambda lets you run code without managing servers.",
 *   "modelId": "amazon.titan-embed-text-v2:0"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimilarityRequest {

    /** First text to compare. */
    @NotBlank(message = "textA must not be blank")
    @Size(max = 50_000, message = "textA must not exceed 50,000 characters")
    private String textA;

    /** Second text to compare. */
    @NotBlank(message = "textB must not be blank")
    @Size(max = 50_000, message = "textB must not exceed 50,000 characters")
    private String textB;

    /**
     * Optional embedding model ID override.
     * Both texts are always embedded with the same model.
     */
    private String modelId;
}
