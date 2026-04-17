package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response body for {@code POST /api/kb/retrieve}.
 *
 * <pre>{@code
 * {
 *   "query": "Lambda cold start mitigation",
 *   "chunks": [
 *     {
 *       "content": "Lambda cold starts can be reduced by...",
 *       "score": 0.8734,
 *       "sourceUri": "s3://my-bucket/docs/lambda-perf.pdf",
 *       "locationType": "S3",
 *       "metadata": { "x-amz-bedrock-kb-source-uri": "s3://..." }
 *     }
 *   ],
 *   "totalChunks": 5,
 *   "knowledgeBaseId": "ABCDEF1234",
 *   "timestamp": "2025-04-17T09:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbRetrieveResponse {

    /** The original search query. */
    private String query;

    /** Retrieved chunks ranked by relevance score descending. */
    private List<RetrievedChunk> chunks;

    /** Total number of chunks returned. */
    private int totalChunks;

    /** The Knowledge Base ID that was queried. */
    private String knowledgeBaseId;

    /** Server-side timestamp. */
    private Instant timestamp;

    // ── Chunk type ────────────────────────────────────────────────────────────

    /**
     * A single document chunk retrieved from the Knowledge Base.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievedChunk {

        /** The chunk text — the actual document excerpt. */
        private String content;

        /**
         * Relevance score (typically 0–1). Higher = more similar to the query.
         * Scores are comparable within a single response but not across different queries.
         */
        private double score;

        /**
         * Source URI where this chunk originated (e.g. {@code s3://bucket/key.pdf}
         * or a web URL).
         */
        private String sourceUri;

        /**
         * Location type: {@code S3}, {@code WEB}, {@code CONFLUENCE},
         * {@code SALESFORCE}, {@code SHAREPOINT}, {@code CUSTOM}, or {@code UNKNOWN}.
         */
        private String locationType;

        /**
         * Raw metadata key-value pairs for this chunk.
         * Includes standard Bedrock keys ({@code x-amz-bedrock-kb-source-uri},
         * {@code x-amz-bedrock-kb-chunk-id}) plus any custom metadata you defined.
         */
        private Map<String, String> metadata;
    }
}
