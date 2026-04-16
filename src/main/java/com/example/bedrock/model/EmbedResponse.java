package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/embeddings/embed}.
 *
 * <pre>{@code
 * {
 *   "embedding": [0.023, -0.141, 0.087, ...],
 *   "dimensions": 1024,
 *   "inputTokenCount": 12,
 *   "modelId": "amazon.titan-embed-text-v2:0",
 *   "timestamp": "2025-04-16T22:00:00Z"
 * }
 * }</pre>
 *
 * <h2>Using the embedding</h2>
 * <p>Pass this vector to a vector database (pgvector, OpenSearch, Pinecone, etc.)
 * for similarity search, or compute cosine similarity in-memory via
 * {@code POST /api/embeddings/similarity}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbedResponse {

    /**
     * The embedding vector. Length equals {@code dimensions}.
     * Each element is a {@code float} in the range {@code [-1, 1]} when normalised.
     */
    private List<Double> embedding;

    /** Number of dimensions in the vector. */
    private int dimensions;

    /** Number of tokens in the input text as counted by the embedding model. */
    private int inputTokenCount;

    /** The embedding model that produced this vector. */
    private String modelId;

    /** Server-side timestamp when the embedding was generated. */
    private Instant timestamp;
}
