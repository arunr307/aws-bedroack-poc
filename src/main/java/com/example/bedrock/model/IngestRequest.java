package com.example.bedrock.model;

import jakarta.validation.Valid;
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
 * Request body for {@code POST /api/rag/ingest}.
 *
 * <h2>Single document</h2>
 * <pre>{@code
 * {
 *   "documents": [
 *     { "title": "AWS Lambda Overview", "content": "AWS Lambda is a serverless..." }
 *   ]
 * }
 * }</pre>
 *
 * <h2>Batch ingest with custom chunking</h2>
 * <pre>{@code
 * {
 *   "documents": [
 *     { "title": "Doc A", "content": "..." },
 *     { "title": "Doc B", "content": "..." }
 *   ],
 *   "chunkSize": 300,
 *   "chunkOverlap": 30
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestRequest {

    /**
     * One or more documents to embed and store.
     * Each document is split into overlapping chunks before embedding.
     */
    @NotEmpty(message = "documents must not be empty")
    @Size(max = 50, message = "maximum 50 documents per ingest request")
    @Valid
    private List<DocumentInput> documents;

    /**
     * Number of words per chunk. Shorter chunks are more precise but
     * miss broader context; longer chunks capture more context but
     * reduce retrieval precision. Default: {@code 200}.
     */
    @Builder.Default
    @Positive(message = "chunkSize must be positive")
    private int chunkSize = 200;

    /**
     * Number of words that overlap between consecutive chunks.
     * Overlap preserves context across chunk boundaries.
     * Must be less than {@code chunkSize}. Default: {@code 20}.
     */
    @Builder.Default
    @Positive(message = "chunkOverlap must be positive")
    private int chunkOverlap = 20;

    // ── Nested input ──────────────────────────────────────────────────────────

    /**
     * A single document to be ingested.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentInput {

        /** Human-readable title shown in query results. */
        @NotBlank(message = "document title must not be blank")
        private String title;

        /** Full text content. Chunked and embedded during ingestion. Max 500 000 chars. */
        @NotBlank(message = "document content must not be blank")
        @Size(max = 500_000, message = "document content must not exceed 500,000 characters")
        private String content;
    }
}
