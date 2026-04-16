package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/embeddings/search}.
 *
 * <pre>{@code
 * {
 *   "query": "serverless compute options on AWS",
 *   "results": [
 *     { "rank": 1, "score": 0.9421, "document": "AWS Lambda runs code without servers." },
 *     { "rank": 2, "score": 0.8934, "document": "AWS Fargate is serverless containers..." },
 *     { "rank": 3, "score": 0.7102, "document": "AWS Step Functions orchestrates..." }
 *   ],
 *   "totalDocuments": 5,
 *   "returnedResults": 3,
 *   "modelId": "amazon.titan-embed-text-v2:0",
 *   "timestamp": "2025-04-16T22:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticSearchResponse {

    /** The original search query. */
    private String query;

    /** Documents ranked by semantic similarity to the query, most relevant first. */
    private List<SearchResult> results;

    /** Total number of documents that were searched. */
    private int totalDocuments;

    /** Number of results actually returned (may be less than {@code topK} if minScore filtered some out). */
    private int returnedResults;

    /** The embedding model used for all vectors. */
    private String modelId;

    /** Server-side timestamp. */
    private Instant timestamp;

    /**
     * A single ranked search result.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResult {

        /** 1-based rank (1 = most similar). */
        private int rank;

        /** Cosine similarity score in {@code [-1, 1]}, rounded to 4 decimal places. */
        private double score;

        /** The original document text. */
        private String document;

        /** Zero-based index of this document in the original {@code documents} list. */
        private int documentIndex;
    }
}
