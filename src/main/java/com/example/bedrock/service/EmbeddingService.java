package com.example.bedrock.service;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates vector embeddings and performs in-memory semantic search
 * using Amazon Bedrock's <strong>Titan Embed Text V2</strong> model
 * via the {@code InvokeModel} API.
 *
 * <h2>Why InvokeModel (not Converse)?</h2>
 * <p>Embedding models produce vectors, not conversational replies — the Converse API
 * does not support them. {@code InvokeModel} sends a raw JSON body and receives
 * a raw JSON body, giving full control over the model-specific payload format.
 *
 * <h2>Titan Embed Text V2 payload</h2>
 * <pre>{@code
 * // Request body (JSON)
 * { "inputText": "...", "dimensions": 1024, "normalize": true }
 *
 * // Response body (JSON)
 * { "embedding": [0.023, -0.141, ...], "inputTextTokenCount": 12 }
 * }</pre>
 *
 * <h2>Cosine similarity</h2>
 * <p>With {@code normalize=true}, Bedrock returns unit-length vectors. Cosine
 * similarity then equals the plain dot product — no division by magnitudes needed.
 * When {@code normalize=false} the full formula is applied:
 * <pre>{@code  similarity = (A · B) / (|A| × |B|) }</pre>
 *
 * <h2>Semantic search</h2>
 * <p>The search endpoint is fully in-memory — no vector database required.
 * For production use, persist embeddings in pgvector, OpenSearch k-NN, or Pinecone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final BedrockRuntimeClient bedrockClient;
    private final BedrockProperties    properties;
    private final ObjectMapper         objectMapper;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates an embedding vector for a single text.
     *
     * @param request embedding request with text and optional overrides
     * @return embedding vector with metadata
     */
    public EmbedResponse embed(EmbedRequest request) {
        String    modelId    = resolveEmbedModelId(request.getModelId());
        int       dimensions = request.getDimensions() != null
                ? request.getDimensions()
                : properties.getBedrock().getEmbedding().getDimensions();
        boolean   normalize  = request.getNormalize() != null
                ? request.getNormalize()
                : properties.getBedrock().getEmbedding().isNormalize();

        TitanEmbedResult result = invokeEmbedding(request.getText(), modelId, dimensions, normalize);

        log.debug("Embedded {} chars → {} dimensions, {} tokens",
                request.getText().length(), result.embedding().size(), result.tokenCount());

        return EmbedResponse.builder()
                .embedding(result.embedding())
                .dimensions(result.embedding().size())
                .inputTokenCount(result.tokenCount())
                .modelId(modelId)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Computes cosine similarity between two texts.
     * Both texts are embedded with the same model, then compared.
     *
     * @param request two texts to compare
     * @return similarity score {@code [-1, 1]} with a human-readable interpretation
     */
    public SimilarityResponse similarity(SimilarityRequest request) {
        String modelId    = resolveEmbedModelId(request.getModelId());
        int    dimensions = properties.getBedrock().getEmbedding().getDimensions();
        boolean normalize = properties.getBedrock().getEmbedding().isNormalize();

        log.debug("Computing similarity — modelId={}", modelId);

        TitanEmbedResult resultA = invokeEmbedding(request.getTextA(), modelId, dimensions, normalize);
        TitanEmbedResult resultB = invokeEmbedding(request.getTextB(), modelId, dimensions, normalize);

        double rawScore = cosineSimilarity(resultA.embedding(), resultB.embedding(), normalize);
        double score    = round4(rawScore);

        return SimilarityResponse.builder()
                .score(score)
                .interpretation(interpret(score))
                .textA(request.getTextA())
                .textB(request.getTextB())
                .modelId(modelId)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Performs semantic search over a list of documents.
     * Embeds the query and all documents, ranks by cosine similarity, and
     * returns the top-K results above {@code minScore}.
     *
     * @param request query, document corpus, and search parameters
     * @return ranked search results
     */
    public SemanticSearchResponse search(SemanticSearchRequest request) {
        String  modelId    = resolveEmbedModelId(request.getModelId());
        int     dimensions = properties.getBedrock().getEmbedding().getDimensions();
        boolean normalize  = properties.getBedrock().getEmbedding().isNormalize();

        log.debug("Semantic search — query='{}', documents={}, topK={}",
                request.getQuery(), request.getDocuments().size(), request.getTopK());

        // Embed the query
        List<Double> queryVec = invokeEmbedding(request.getQuery(), modelId, dimensions, normalize)
                .embedding();

        // Embed each document and score it
        List<SemanticSearchResponse.SearchResult> scored = new ArrayList<>();
        for (int i = 0; i < request.getDocuments().size(); i++) {
            String doc    = request.getDocuments().get(i);
            List<Double> docVec = invokeEmbedding(doc, modelId, dimensions, normalize).embedding();
            double score  = round4(cosineSimilarity(queryVec, docVec, normalize));

            if (score >= request.getMinScore()) {
                scored.add(SemanticSearchResponse.SearchResult.builder()
                        .score(score)
                        .document(doc)
                        .documentIndex(i)
                        .build());
            }
        }

        // Sort descending by score, assign ranks, apply topK
        scored.sort(Comparator.comparingDouble(SemanticSearchResponse.SearchResult::getScore).reversed());

        int limit = request.getTopK() > 0
                ? Math.min(request.getTopK(), scored.size())
                : scored.size();

        List<SemanticSearchResponse.SearchResult> topResults = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            SemanticSearchResponse.SearchResult r = scored.get(i);
            r.setRank(i + 1);
            topResults.add(r);
        }

        log.debug("Search complete — matched {}/{} documents, returning {}",
                scored.size(), request.getDocuments().size(), topResults.size());

        return SemanticSearchResponse.builder()
                .query(request.getQuery())
                .results(topResults)
                .totalDocuments(request.getDocuments().size())
                .returnedResults(topResults.size())
                .modelId(modelId)
                .timestamp(Instant.now())
                .build();
    }

    // ── Package-level helper used by RagService ───────────────────────────────

    /**
     * Embeds a single text using the configured defaults.
     * Intended for internal use by {@link RagService} — avoids duplicating
     * the InvokeModel call logic.
     *
     * @param text text to embed
     * @return normalised embedding vector
     */
    List<Double> embedText(String text) {
        BedrockProperties.Embedding cfg = properties.getBedrock().getEmbedding();
        return invokeEmbedding(text, cfg.getModelId(), cfg.getDimensions(), cfg.isNormalize())
                .embedding();
    }

    /**
     * Cosine similarity between two vectors — exposed for use by {@link RagService}.
     */
    double similarity(List<Double> a, List<Double> b) {
        return cosineSimilarity(a, b, properties.getBedrock().getEmbedding().isNormalize());
    }

    // ── Bedrock invocation ────────────────────────────────────────────────────

    /**
     * Calls the Titan Embed Text V2 model via {@code InvokeModel} and returns
     * the embedding vector and token count.
     */
    private TitanEmbedResult invokeEmbedding(String text, String modelId,
                                              int dimensions, boolean normalize) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("inputText", text);
            payload.put("dimensions", dimensions);
            payload.put("normalize",  normalize);

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(modelId)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(payload)))
                    .build();

            InvokeModelResponse response = bedrockClient.invokeModel(request);

            JsonNode root       = objectMapper.readTree(response.body().asByteArray());
            JsonNode embeddingNode = root.get("embedding");
            int      tokenCount = root.path("inputTextTokenCount").asInt(0);

            if (embeddingNode == null || !embeddingNode.isArray()) {
                throw new BedrockException("Embedding model returned no 'embedding' array");
            }

            List<Double> vector = new ArrayList<>(embeddingNode.size());
            for (JsonNode val : embeddingNode) {
                vector.add(val.asDouble());
            }

            return new TitanEmbedResult(vector, tokenCount);

        } catch (BedrockException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BedrockException("InvokeModel (embedding) failed: " + ex.getMessage(), ex);
        }
    }

    // ── Math ──────────────────────────────────────────────────────────────────

    /**
     * Computes cosine similarity between two equal-length vectors.
     *
     * <p>When both vectors are already L2-normalised ({@code normalize=true}),
     * the dot product equals the cosine similarity — magnitudes are 1.
     * Otherwise the full formula is used.
     *
     * @param a         first vector
     * @param b         second vector
     * @param normalised whether both vectors are already unit-length
     * @return cosine similarity in {@code [-1.0, 1.0]}
     */
    private double cosineSimilarity(List<Double> a, List<Double> b, boolean normalised) {
        if (a.size() != b.size()) {
            throw new BedrockException(
                    "Vector dimension mismatch: " + a.size() + " vs " + b.size());
        }

        double dot = 0.0, magA = 0.0, magB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            dot  += a.get(i) * b.get(i);
            magA += a.get(i) * a.get(i);
            magB += b.get(i) * b.get(i);
        }

        if (normalised) return dot;   // magnitudes are 1 when normalised

        double denom = Math.sqrt(magA) * Math.sqrt(magB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    /**
     * Maps a cosine similarity score to a human-readable label.
     */
    private String interpret(double score) {
        if (score >= 0.90) return "Very similar";
        if (score >= 0.75) return "Closely related";
        if (score >= 0.50) return "Somewhat related";
        if (score >= 0.25) return "Weakly related";
        return "Unrelated / opposite";
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private String resolveEmbedModelId(String requestOverride) {
        if (requestOverride != null && !requestOverride.isBlank()) return requestOverride;
        return properties.getBedrock().getEmbedding().getModelId();
    }

    // ── Internal record ───────────────────────────────────────────────────────

    /** Holds the raw result from a single embedding invocation. */
    private record TitanEmbedResult(List<Double> embedding, int tokenCount) {}
}
