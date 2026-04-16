package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/rag/ingest}.
 *
 * <pre>{@code
 * {
 *   "documentIds": ["d1a2b3c4-...", "e5f6a7b8-..."],
 *   "ingestedDocuments": 2,
 *   "totalChunks": 18,
 *   "embeddingModel": "amazon.titan-embed-text-v2:0",
 *   "timestamp": "2025-04-17T09:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestResponse {

    /**
     * UUIDs assigned to each ingested document, in the same order as the request.
     * Use these IDs with {@code DELETE /api/rag/documents/{id}} to remove a document.
     */
    private List<String> documentIds;

    /** Number of documents successfully ingested. */
    private int ingestedDocuments;

    /** Total number of chunks created across all documents. */
    private int totalChunks;

    /** The embedding model used to embed all chunks. */
    private String embeddingModel;

    /** Server-side timestamp of ingestion completion. */
    private Instant timestamp;
}
