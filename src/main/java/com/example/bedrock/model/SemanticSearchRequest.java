package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for {@code POST /api/embeddings/search}.
 *
 * <p>Embeds the query and each document, then ranks documents by cosine similarity.
 * All embedding happens in-memory — no external vector database required.
 *
 * <pre>{@code
 * {
 *   "query": "serverless compute options on AWS",
 *   "documents": [
 *     "AWS Lambda runs code without servers.",
 *     "Amazon EC2 provides virtual machines in the cloud.",
 *     "AWS Fargate is serverless containers on ECS or EKS.",
 *     "Amazon S3 stores objects at massive scale.",
 *     "AWS Step Functions orchestrates serverless workflows."
 *   ],
 *   "topK": 3
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticSearchRequest {

    /** The search query to find semantically similar documents for. */
    @NotBlank(message = "query must not be blank")
    @Size(max = 10_000, message = "query must not exceed 10,000 characters")
    private String query;

    /**
     * The corpus to search. Each entry is a document (sentence, paragraph, or full text).
     * Maximum 100 documents per request for this in-memory POC.
     */
    @NotEmpty(message = "documents must not be empty")
    @Size(max = 100, message = "documents must not exceed 100 entries per request")
    private List<@NotBlank @Size(max = 50_000) String> documents;

    /**
     * Maximum number of results to return, ranked by relevance.
     * Defaults to {@code 5}. Set to {@code -1} to return all documents ranked.
     */
    @Builder.Default
    @Positive(message = "topK must be a positive number")
    private int topK = 5;

    /**
     * Optional minimum similarity score threshold (0.0–1.0).
     * Results below this score are excluded. Defaults to {@code 0.0} (no filter).
     */
    @Builder.Default
    private double minScore = 0.0;

    /**
     * Optional embedding model ID override.
     * Defaults to {@code aws.bedrock.embedding.model-id} in {@code application.yml}.
     */
    private String modelId;
}
