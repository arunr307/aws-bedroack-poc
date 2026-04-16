package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/rag/query}.
 *
 * <pre>{@code
 * {
 *   "question": "What is AWS Lambda?",
 *   "answer": "AWS Lambda is a serverless compute service that runs your code in
 *              response to events without requiring you to manage servers.",
 *   "sources": [
 *     {
 *       "documentId": "d1a2b3c4-...",
 *       "title": "AWS Lambda Overview",
 *       "chunkText": "AWS Lambda is a serverless compute service...",
 *       "score": 0.9421,
 *       "chunkIndex": 0
 *     }
 *   ],
 *   "retrievedChunks": 3,
 *   "generationModelId": "amazon.nova-lite-v1:0",
 *   "embeddingModelId": "amazon.titan-embed-text-v2:0",
 *   "usage": { "inputTokens": 540, "outputTokens": 82, "totalTokens": 622 },
 *   "timestamp": "2025-04-17T09:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagQueryResponse {

    /** The original question. */
    private String question;

    /** The model's answer, grounded in the retrieved context. */
    private String answer;

    /**
     * The document chunks that were injected as context, ranked by relevance.
     * Inspect these to understand why the model answered the way it did.
     */
    private List<SourceChunk> sources;

    /** Number of chunks that were retrieved and used as context. */
    private int retrievedChunks;

    /** The Bedrock model used for answer generation (Converse API). */
    private String generationModelId;

    /** The Bedrock model used for embedding the question and chunks. */
    private String embeddingModelId;

    /** Token usage for the generation step. */
    private ChatResponse.TokenUsage usage;

    /** Server-side timestamp. */
    private Instant timestamp;

    // ── Source chunk ──────────────────────────────────────────────────────────

    /**
     * A document chunk that was retrieved and used as context for this answer.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceChunk {

        /** ID of the parent document in the store. */
        private String documentId;

        /** Human-readable title of the parent document. */
        private String title;

        /** The chunk text that was injected into the prompt. */
        private String chunkText;

        /** Cosine similarity score between this chunk and the query (0–1). */
        private double score;

        /** Zero-based index of this chunk within its parent document. */
        private int chunkIndex;
    }
}
