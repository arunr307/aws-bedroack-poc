package com.example.bedrock.controller;

import com.example.bedrock.model.*;
import com.example.bedrock.service.EmbeddingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller exposing the Embeddings API.
 *
 * <h2>Base path</h2>
 * {@code /api/embeddings}
 *
 * <h2>Endpoints</h2>
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/embeddings/embed</td>
 *       <td>Generate a vector embedding for a single text</td></tr>
 *   <tr><td>POST</td><td>/api/embeddings/similarity</td>
 *       <td>Compute cosine similarity between two texts</td></tr>
 *   <tr><td>POST</td><td>/api/embeddings/search</td>
 *       <td>Semantic search — rank a corpus by similarity to a query</td></tr>
 * </table>
 *
 * <h2>Quick examples</h2>
 * <pre>{@code
 * # Get an embedding vector
 * curl -X POST http://localhost:8080/api/embeddings/embed \
 *      -H "Content-Type: application/json" \
 *      -d '{"text":"AWS Lambda is serverless compute."}'
 *
 * # Compare two texts
 * curl -X POST http://localhost:8080/api/embeddings/similarity \
 *      -H "Content-Type: application/json" \
 *      -d '{"textA":"AWS Lambda","textB":"Serverless functions on AWS"}'
 *
 * # Semantic search
 * curl -X POST http://localhost:8080/api/embeddings/search \
 *      -H "Content-Type: application/json" \
 *      -d '{"query":"serverless compute","documents":["Lambda","EC2","S3"],"topK":2}'
 * }</pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/embeddings")
@RequiredArgsConstructor
public class EmbeddingController {

    private final EmbeddingService embeddingService;

    /**
     * Generates a vector embedding for a single piece of text.
     *
     * <p>The returned vector can be stored in a vector database (pgvector,
     * OpenSearch k-NN, Pinecone, etc.) for large-scale similarity search.
     *
     * @param request text and optional dimension / normalisation overrides
     * @return embedding vector with dimension count and token usage
     */
    @PostMapping("/embed")
    public ResponseEntity<EmbedResponse> embed(@Valid @RequestBody EmbedRequest request) {
        log.info("Embed request — textLength={}", request.getText().length());

        EmbedResponse response = embeddingService.embed(request);

        log.info("Embed complete — dimensions={}, tokens={}",
                response.getDimensions(), response.getInputTokenCount());

        return ResponseEntity.ok(response);
    }

    /**
     * Computes the cosine similarity between two texts.
     *
     * <p>Both texts are embedded with the same model and the dot product of
     * their normalised vectors is returned as a score in {@code [-1, 1]}.
     * Score {@code ≥ 0.90} indicates near-duplicate content.
     *
     * @param request two texts to compare
     * @return similarity score and human-readable interpretation
     */
    @PostMapping("/similarity")
    public ResponseEntity<SimilarityResponse> similarity(@Valid @RequestBody SimilarityRequest request) {
        log.info("Similarity request — textA length={}, textB length={}",
                request.getTextA().length(), request.getTextB().length());

        SimilarityResponse response = embeddingService.similarity(request);

        log.info("Similarity score={} ({})", response.getScore(), response.getInterpretation());

        return ResponseEntity.ok(response);
    }

    /**
     * Performs in-memory semantic search over a list of documents.
     *
     * <p>Each document and the query are embedded independently, then ranked
     * by cosine similarity. The top-K most relevant documents are returned.
     * No vector database or index is required — this is a self-contained POC.
     *
     * @param request query, document corpus, topK limit, and optional minScore filter
     * @return ranked search results with scores
     */
    @PostMapping("/search")
    public ResponseEntity<SemanticSearchResponse> search(@Valid @RequestBody SemanticSearchRequest request) {
        log.info("Semantic search — documents={}, topK={}, minScore={}",
                request.getDocuments().size(), request.getTopK(), request.getMinScore());

        SemanticSearchResponse response = embeddingService.search(request);

        log.info("Search complete — returned {}/{} documents",
                response.getReturnedResults(), response.getTotalDocuments());

        return ResponseEntity.ok(response);
    }
}
