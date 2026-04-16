package com.example.bedrock.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory vector store for RAG.
 *
 * <p>Stores document chunks together with their pre-computed embedding vectors.
 * All retrieval is brute-force linear scan — acceptable for a POC with hundreds
 * of chunks. For production, replace with a vector database such as
 * pgvector (PostgreSQL), Amazon OpenSearch k-NN, or Pinecone.
 *
 * <h2>Data model</h2>
 * <pre>
 * DocumentStore
 *   └── StoredDocument (id, title, content, createdAt)
 *         └── List&lt;Chunk&gt; (text, embedding, chunkIndex)
 * </pre>
 *
 * <h2>Thread safety</h2>
 * <p>Uses {@link ConcurrentHashMap} for the document map and
 * {@link CopyOnWriteArrayList} for chunk lists. Retrieval (findSimilar) is
 * read-only and safe to call concurrently with ingest.
 */
@Slf4j
@Component
public class DocumentStore {

    /** Map of documentId → StoredDocument. */
    private final ConcurrentHashMap<String, StoredDocument> store = new ConcurrentHashMap<>();

    // ── Write operations ──────────────────────────────────────────────────────

    /**
     * Persists a document with its pre-computed chunks and returns its generated ID.
     *
     * @param title    human-readable document title
     * @param content  full original text (stored for reference)
     * @param chunks   list of {@link Chunk} objects with text and embedding vectors
     * @return UUID assigned to this document
     */
    public String save(String title, String content, List<Chunk> chunks) {
        String id = UUID.randomUUID().toString();
        store.put(id, new StoredDocument(id, title, content,
                new CopyOnWriteArrayList<>(chunks), Instant.now()));
        log.debug("Stored document id={}, title='{}', chunks={}", id, title, chunks.size());
        return id;
    }

    /**
     * Removes a document by ID.
     *
     * @param id document ID
     * @return {@code true} if the document existed and was removed
     */
    public boolean remove(String id) {
        boolean removed = store.remove(id) != null;
        if (removed) log.debug("Removed document id={}", id);
        return removed;
    }

    /** Removes all documents from the store. */
    public void clear() {
        int count = store.size();
        store.clear();
        log.debug("Cleared store — removed {} documents", count);
    }

    // ── Read operations ───────────────────────────────────────────────────────

    /** Returns all stored documents (unmodifiable snapshot). */
    public List<StoredDocument> getAll() {
        return List.copyOf(store.values());
    }

    /** Finds a document by ID. */
    public Optional<StoredDocument> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /** Total number of documents in the store. */
    public int documentCount() {
        return store.size();
    }

    /** Total number of chunks across all documents. */
    public int chunkCount() {
        return store.values().stream()
                .mapToInt(d -> d.chunks().size())
                .sum();
    }

    /**
     * Searches all chunks for those most similar to {@code queryEmbedding}.
     *
     * <p>Performs a brute-force linear scan — O(n) over all chunks.
     * Results are pre-sorted descending by score and capped at {@code topK}.
     *
     * @param queryEmbedding embedding vector of the search query
     * @param topK           maximum results to return
     * @param minScore       minimum cosine similarity threshold
     * @param scoreFn        function to compute similarity between two vectors
     * @return list of {@link ChunkMatch}, sorted by score descending
     */
    public List<ChunkMatch> findSimilar(List<Double> queryEmbedding, int topK,
                                        double minScore,
                                        java.util.function.BiFunction<List<Double>,
                                                List<Double>, Double> scoreFn) {
        List<ChunkMatch> matches = new ArrayList<>();

        for (StoredDocument doc : store.values()) {
            for (Chunk chunk : doc.chunks()) {
                double score = scoreFn.apply(queryEmbedding, chunk.embedding());
                if (score >= minScore) {
                    matches.add(new ChunkMatch(
                            doc.id(), doc.title(), chunk.text(),
                            score, chunk.chunkIndex()));
                }
            }
        }

        matches.sort(Comparator.comparingDouble(ChunkMatch::score).reversed());

        return matches.subList(0, Math.min(topK, matches.size()));
    }

    // ── Domain types ──────────────────────────────────────────────────────────

    /**
     * A stored document with its chunks.
     *
     * @param id        UUID of the document
     * @param title     human-readable title
     * @param content   full original text
     * @param chunks    embedded chunks
     * @param createdAt ingestion timestamp
     */
    public record StoredDocument(
            String id,
            String title,
            String content,
            List<Chunk> chunks,
            Instant createdAt) {}

    /**
     * A single chunk: a slice of the document text with its embedding.
     *
     * @param text       chunk text
     * @param embedding  embedding vector for this chunk
     * @param chunkIndex zero-based position of this chunk within its document
     */
    public record Chunk(String text, List<Double> embedding, int chunkIndex) {}

    /**
     * A retrieval result — a chunk that matched a query above the score threshold.
     *
     * @param documentId  ID of the parent document
     * @param title       title of the parent document
     * @param chunkText   the matching chunk text
     * @param score       cosine similarity score
     * @param chunkIndex  position within the parent document
     */
    public record ChunkMatch(
            String documentId,
            String title,
            String chunkText,
            double score,
            int chunkIndex) {}
}
