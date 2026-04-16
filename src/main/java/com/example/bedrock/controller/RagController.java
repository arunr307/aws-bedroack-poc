package com.example.bedrock.controller;

import com.example.bedrock.model.IngestRequest;
import com.example.bedrock.model.IngestResponse;
import com.example.bedrock.model.RagQueryRequest;
import com.example.bedrock.model.RagQueryResponse;
import com.example.bedrock.service.DocumentStore;
import com.example.bedrock.service.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the DIY RAG (Retrieval-Augmented Generation) pipeline.
 *
 * <h2>Workflow</h2>
 * <ol>
 *   <li><strong>Ingest</strong> — {@code POST /api/rag/ingest} — chunk, embed, and store documents.</li>
 *   <li><strong>Query</strong>  — {@code POST /api/rag/query}  — retrieve relevant chunks and generate a grounded answer.</li>
 * </ol>
 *
 * <h2>Management endpoints</h2>
 * <ul>
 *   <li>{@code GET    /api/rag/documents}       — list all stored documents</li>
 *   <li>{@code DELETE /api/rag/documents/{id}}  — remove a single document</li>
 *   <li>{@code DELETE /api/rag/documents}        — clear the entire store</li>
 *   <li>{@code GET    /api/rag/health}           — store statistics</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService    ragService;
    private final DocumentStore documentStore;

    // ── Ingest ────────────────────────────────────────────────────────────────

    /**
     * Ingests one or more documents into the knowledge base.
     *
     * <p>Each document is split into overlapping word-based chunks, each chunk
     * is embedded via Titan Embed Text V2, and the embeddings are stored in memory.
     *
     * <h3>Minimal request</h3>
     * <pre>{@code
     * {
     *   "documents": [
     *     { "title": "AWS Lambda Overview", "content": "AWS Lambda is a serverless..." }
     *   ]
     * }
     * }</pre>
     *
     * <h3>With custom chunking</h3>
     * <pre>{@code
     * {
     *   "documents": [{ "title": "...", "content": "..." }],
     *   "chunkSize": 300,
     *   "chunkOverlap": 30
     * }
     * }</pre>
     *
     * @param request one or more documents plus optional chunking parameters
     * @return document IDs and chunk statistics
     */
    @PostMapping("/ingest")
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest request) {
        log.info("POST /api/rag/ingest — {} document(s)", request.getDocuments().size());
        IngestResponse response = ragService.ingest(request);
        return ResponseEntity.ok(response);
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * Answers a question using the ingested knowledge base.
     *
     * <p>The question is embedded, the most relevant chunks are retrieved from
     * the store, and a Bedrock model generates a grounded answer.
     *
     * <h3>Minimal request</h3>
     * <pre>{@code
     * { "question": "What is AWS Lambda?" }
     * }</pre>
     *
     * <h3>Full control</h3>
     * <pre>{@code
     * {
     *   "question": "What is AWS Lambda and how does pricing work?",
     *   "topK": 5,
     *   "minScore": 0.65,
     *   "modelId": "amazon.nova-pro-v1:0",
     *   "systemPrompt": "You are an AWS Solutions Architect. Be concise."
     * }
     * }</pre>
     *
     * @param request question and optional retrieval/generation parameters
     * @return model's answer, source chunks, and token usage
     */
    @PostMapping("/query")
    public ResponseEntity<RagQueryResponse> query(@Valid @RequestBody RagQueryRequest request) {
        log.info("POST /api/rag/query — question='{}'", request.getQuestion());
        RagQueryResponse response = ragService.query(request);
        return ResponseEntity.ok(response);
    }

    // ── Document management ───────────────────────────────────────────────────

    /**
     * Returns a summary of all documents currently in the store.
     * Chunk texts and embedding vectors are omitted to keep the response compact.
     *
     * @return list of document metadata (id, title, chunk count, createdAt)
     */
    @GetMapping("/documents")
    public ResponseEntity<List<Map<String, Object>>> listDocuments() {
        List<Map<String, Object>> docs = documentStore.getAll().stream()
                .map(doc -> Map.<String, Object>of(
                        "id",         doc.id(),
                        "title",      doc.title(),
                        "chunkCount", doc.chunks().size(),
                        "createdAt",  doc.createdAt()))
                .toList();
        return ResponseEntity.ok(docs);
    }

    /**
     * Removes a single document from the store.
     *
     * @param id document ID returned by the ingest endpoint
     * @return 200 with confirmation, or 404 if the ID was not found
     */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable String id) {
        boolean removed = documentStore.remove(id);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        log.info("DELETE /api/rag/documents/{} — removed", id);
        return ResponseEntity.ok(Map.of(
                "id",      id,
                "removed", true,
                "message", "Document removed from the knowledge base"));
    }

    /**
     * Removes all documents from the store. Use with care — this is irreversible.
     *
     * @return confirmation with the previous document and chunk counts
     */
    @DeleteMapping("/documents")
    public ResponseEntity<Map<String, Object>> clearDocuments() {
        int docs   = documentStore.documentCount();
        int chunks = documentStore.chunkCount();
        documentStore.clear();
        log.info("DELETE /api/rag/documents — cleared {} documents, {} chunks", docs, chunks);
        return ResponseEntity.ok(Map.of(
                "cleared",          true,
                "documentsRemoved", docs,
                "chunksRemoved",    chunks,
                "message",          "Knowledge base cleared"));
    }

    // ── Health ────────────────────────────────────────────────────────────────

    /**
     * Returns store statistics — useful for verifying that ingest succeeded.
     *
     * @return document count, chunk count, and server timestamp
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status",        "UP",
                "documentCount", documentStore.documentCount(),
                "chunkCount",    documentStore.chunkCount(),
                "timestamp",     Instant.now()));
    }
}
