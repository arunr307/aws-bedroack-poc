package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response body for {@code POST /api/kb/query}.
 *
 * <pre>{@code
 * {
 *   "question": "What is AWS Lambda?",
 *   "answer": "AWS Lambda is a serverless compute service that runs your code...",
 *   "sessionId": "sess-abc123",
 *   "citations": [
 *     {
 *       "content": "AWS Lambda is a serverless, event-driven compute service...",
 *       "sourceUri": "s3://my-bucket/docs/lambda-overview.pdf",
 *       "locationType": "S3",
 *       "metadata": { "x-amz-bedrock-kb-source-uri": "s3://..." }
 *     }
 *   ],
 *   "citationCount": 1,
 *   "knowledgeBaseId": "ABCDEF1234",
 *   "modelId": "amazon.nova-lite-v1:0",
 *   "timestamp": "2025-04-17T09:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbQueryResponse {

    /** The original question. */
    private String question;

    /** The model's answer, grounded in the retrieved Knowledge Base chunks. */
    private String answer;

    /**
     * Session ID assigned by Bedrock for this conversation.
     * Pass this back in subsequent requests to continue the same session
     * (Bedrock maintains history server-side).
     */
    private String sessionId;

    /**
     * The source chunks the model used to generate the answer.
     * Each citation corresponds to a document chunk retrieved from the Knowledge Base.
     */
    private List<Citation> citations;

    /** Total number of source citations used in the answer. */
    private int citationCount;

    /** The Knowledge Base ID that was queried. */
    private String knowledgeBaseId;

    /** The Bedrock model used for answer generation. */
    private String modelId;

    /** Server-side timestamp. */
    private Instant timestamp;

    // ── Citation type ─────────────────────────────────────────────────────────

    /**
     * A document chunk retrieved from the Knowledge Base and used as grounding
     * context for the generated answer.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Citation {

        /**
         * The chunk text that was injected as context.
         * This is the actual content the model "read" before generating the answer.
         */
        private String content;

        /**
         * The source URI for this chunk — typically an S3 object URI
         * (e.g. {@code s3://my-bucket/docs/lambda.pdf}) or a web URL.
         */
        private String sourceUri;

        /**
         * Type of source location: {@code S3}, {@code WEB}, {@code CONFLUENCE},
         * {@code SALESFORCE}, {@code SHAREPOINT}, {@code CUSTOM}, or {@code UNKNOWN}.
         */
        private String locationType;

        /**
         * Raw metadata key-value pairs attached to the source document in the Knowledge Base.
         * Common keys include {@code x-amz-bedrock-kb-source-uri},
         * {@code x-amz-bedrock-kb-chunk-id}, and any custom metadata you configured.
         */
        private Map<String, String> metadata;
    }
}
