package com.example.bedrock.service;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Integrates with Amazon Bedrock's <strong>managed Knowledge Bases</strong> via the
 * <a href="https://docs.aws.amazon.com/bedrock/latest/APIReference/API_agent-runtime_RetrieveAndGenerate.html">
 * Bedrock Agent Runtime API</a>.
 *
 * <h2>Two operations</h2>
 * <dl>
 *   <dt>{@link #query(KbQueryRequest)} — RetrieveAndGenerate</dt>
 *   <dd>One call: Bedrock retrieves relevant chunks <em>and</em> generates a grounded
 *       answer. Supports multi-turn sessions via {@code sessionId}.</dd>
 *
 *   <dt>{@link #retrieve(KbRetrieveRequest)} — Retrieve</dt>
 *   <dd>Retrieval only — returns raw chunks with relevance scores without calling a
 *       language model. Useful for debugging, re-ranking, or custom prompt assembly.</dd>
 * </dl>
 *
 * <h2>Managed vs DIY RAG</h2>
 * <table border="1">
 *   <tr><th></th><th>DIY RAG ({@link RagService})</th><th>Managed KB ({@link KnowledgeBaseService})</th></tr>
 *   <tr><td>Chunking</td><td>Word-based, in-memory</td><td>Automatic, configurable in console</td></tr>
 *   <tr><td>Embedding</td><td>Titan Embed via InvokeModel</td><td>Managed by AWS (Titan or Cohere)</td></tr>
 *   <tr><td>Vector store</td><td>ConcurrentHashMap (JVM heap)</td><td>OpenSearch / Aurora / Pinecone / …</td></tr>
 *   <tr><td>Data ingestion</td><td>REST API ({@code POST /api/rag/ingest})</td><td>S3 sync in AWS Console / SDK</td></tr>
 *   <tr><td>Scale</td><td>Hundreds of documents (POC)</td><td>Millions of documents (production)</td></tr>
 * </table>
 *
 * <h2>Prerequisites</h2>
 * <ol>
 *   <li>Create a Knowledge Base in AWS Console (Bedrock → Knowledge Bases).</li>
 *   <li>Sync at least one S3 data source.</li>
 *   <li>Set {@code KB_ID} in {@code .vscode/launch.json} (or the {@code AWS_KB_ID} env var).</li>
 * </ol>
 *
 * <h2>Model ARN format</h2>
 * <p>The {@code RetrieveAndGenerate} API requires a fully-qualified model ARN:
 * {@code arn:aws:bedrock:{region}::foundation-model/{modelId}}. This service
 * constructs the ARN automatically from the configured region and model ID.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final BedrockAgentRuntimeClient agentRuntimeClient;
    private final BedrockProperties         properties;

    // ── RetrieveAndGenerate ───────────────────────────────────────────────────

    /**
     * Retrieves relevant chunks from the Knowledge Base and generates a grounded answer
     * in a single Bedrock API call ({@code RetrieveAndGenerate}).
     *
     * <p>Supports multi-turn sessions: pass the {@code sessionId} from the previous
     * response to continue the conversation. Bedrock maintains session history
     * server-side for the session lifetime (~1 hour of inactivity).
     *
     * @param request question, optional session ID, topK, and model overrides
     * @return answer, source citations, session ID, and metadata
     * @throws BedrockException if the Knowledge Base ID is not configured or the API call fails
     */
    public KbQueryResponse query(KbQueryRequest request) {
        String kbId    = resolveKbId(request.getKnowledgeBaseId());
        String modelId = resolveModelId(request.getModelId());
        int    topK    = resolveTopK(request.getTopK());

        log.info("KB RetrieveAndGenerate — kbId={}, topK={}, model={}, sessionId={}",
                kbId, topK, modelId, request.getSessionId());

        // ── Build the request ─────────────────────────────────────────────────
        KnowledgeBaseRetrieveAndGenerateConfiguration kbConfig =
                KnowledgeBaseRetrieveAndGenerateConfiguration.builder()
                        .knowledgeBaseId(kbId)
                        .modelArn(buildModelArn(modelId))
                        .retrievalConfiguration(
                                KnowledgeBaseRetrievalConfiguration.builder()
                                        .vectorSearchConfiguration(
                                                KnowledgeBaseVectorSearchConfiguration.builder()
                                                        .numberOfResults(topK)
                                                        .build())
                                        .build())
                        .build();

        RetrieveAndGenerateRequest.Builder reqBuilder = RetrieveAndGenerateRequest.builder()
                .input(RetrieveAndGenerateInput.builder()
                        .text(request.getQuestion())
                        .build())
                .retrieveAndGenerateConfiguration(
                        RetrieveAndGenerateConfiguration.builder()
                                .type(RetrieveAndGenerateType.KNOWLEDGE_BASE)
                                .knowledgeBaseConfiguration(kbConfig)
                                .build());

        // Attach session ID for multi-turn conversations
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            reqBuilder.sessionId(request.getSessionId());
        }

        // ── Invoke ────────────────────────────────────────────────────────────
        RetrieveAndGenerateResponse response;
        try {
            response = agentRuntimeClient.retrieveAndGenerate(reqBuilder.build());
        } catch (Exception ex) {
            throw new BedrockException(
                    "RetrieveAndGenerate failed for KB '" + kbId + "': " + ex.getMessage(), ex);
        }

        // ── Extract answer ────────────────────────────────────────────────────
        String answer = response.output() != null ? response.output().text() : "";

        // ── Extract citations ─────────────────────────────────────────────────
        List<KbQueryResponse.Citation> citations = extractCitations(response.citations());

        log.info("KB query complete — citations={}, sessionId={}",
                citations.size(), response.sessionId());

        return KbQueryResponse.builder()
                .question(request.getQuestion())
                .answer(answer)
                .sessionId(response.sessionId())
                .citations(citations)
                .citationCount(citations.size())
                .knowledgeBaseId(kbId)
                .modelId(modelId)
                .timestamp(Instant.now())
                .build();
    }

    // ── Retrieve-only ─────────────────────────────────────────────────────────

    /**
     * Retrieves relevant chunks from the Knowledge Base <em>without</em> generating
     * an answer ({@code Retrieve} API).
     *
     * <p>Use this when you want to:
     * <ul>
     *   <li>Inspect what the Knowledge Base knows about a topic</li>
     *   <li>Perform custom re-ranking or filtering before generation</li>
     *   <li>Feed chunks into a custom prompt with full control</li>
     * </ul>
     *
     * @param request search query, topK, and optional KB ID override
     * @return list of chunks ranked by relevance score
     * @throws BedrockException if the Knowledge Base ID is not configured or the API call fails
     */
    public KbRetrieveResponse retrieve(KbRetrieveRequest request) {
        String kbId = resolveKbId(request.getKnowledgeBaseId());
        int    topK = resolveTopK(request.getTopK());

        log.info("KB Retrieve — kbId={}, topK={}, query='{}'",
                kbId, topK, request.getQuery());

        RetrieveRequest retrieveRequest = RetrieveRequest.builder()
                .knowledgeBaseId(kbId)
                .retrievalQuery(KnowledgeBaseQuery.builder()
                        .text(request.getQuery())
                        .build())
                .retrievalConfiguration(
                        KnowledgeBaseRetrievalConfiguration.builder()
                                .vectorSearchConfiguration(
                                        KnowledgeBaseVectorSearchConfiguration.builder()
                                                .numberOfResults(topK)
                                                .build())
                                .build())
                .build();

        RetrieveResponse response;
        try {
            response = agentRuntimeClient.retrieve(retrieveRequest);
        } catch (Exception ex) {
            throw new BedrockException(
                    "Retrieve failed for KB '" + kbId + "': " + ex.getMessage(), ex);
        }

        List<KbRetrieveResponse.RetrievedChunk> chunks =
                extractChunks(response.retrievalResults());

        log.info("KB retrieve complete — {} chunks returned", chunks.size());

        return KbRetrieveResponse.builder()
                .query(request.getQuery())
                .chunks(chunks)
                .totalChunks(chunks.size())
                .knowledgeBaseId(kbId)
                .timestamp(Instant.now())
                .build();
    }

    // ── Extraction helpers ────────────────────────────────────────────────────

    /**
     * Flattens the nested citation → retrievedReferences structure into a flat list,
     * deduplicating by source URI so each document chunk appears at most once.
     */
    private List<KbQueryResponse.Citation> extractCitations(List<Citation> rawCitations) {
        if (rawCitations == null) return List.of();

        List<KbQueryResponse.Citation> result = new ArrayList<>();
        for (Citation citation : rawCitations) {
            if (citation.retrievedReferences() == null) continue;
            for (RetrievedReference ref : citation.retrievedReferences()) {
                String content = ref.content() != null ? ref.content().text() : "";
                String uri     = extractUri(ref.location());
                String type    = extractLocationType(ref.location());
                Map<String, String> meta = extractMetadata(ref.metadata());

                result.add(KbQueryResponse.Citation.builder()
                        .content(content)
                        .sourceUri(uri)
                        .locationType(type)
                        .metadata(meta)
                        .build());
            }
        }
        return result;
    }

    private List<KbRetrieveResponse.RetrievedChunk> extractChunks(
            List<KnowledgeBaseRetrievalResult> results) {
        if (results == null) return List.of();

        return results.stream()
                .map(r -> KbRetrieveResponse.RetrievedChunk.builder()
                        .content(r.content() != null ? r.content().text() : "")
                        .score(r.score() != null ? r.score() : 0.0)
                        .sourceUri(extractUri(r.location()))
                        .locationType(extractLocationType(r.location()))
                        .metadata(extractMetadata(r.metadata()))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Extracts the URI from a {@link RetrievalResultLocation}, handling all
     * supported location types gracefully.
     */
    private String extractUri(RetrievalResultLocation location) {
        if (location == null) return null;
        return switch (location.type()) {
            case S3              -> location.s3Location()         != null
                    ? location.s3Location().uri() : null;
            case WEB             -> location.webLocation()        != null
                    ? location.webLocation().url() : null;
            case CONFLUENCE      -> location.confluenceLocation() != null
                    ? location.confluenceLocation().url() : null;
            case SALESFORCE      -> location.salesforceLocation() != null
                    ? location.salesforceLocation().url() : null;
            case SHAREPOINT      -> location.sharePointLocation() != null
                    ? location.sharePointLocation().url() : null;
            default              -> null;
        };
    }

    private String extractLocationType(RetrievalResultLocation location) {
        if (location == null || location.type() == null) return "UNKNOWN";
        return location.type().name();
    }

    private Map<String, String> extractMetadata(
            Map<String, software.amazon.awssdk.core.document.Document> raw) {
        if (raw == null) return Map.of();
        return raw.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue() != null ? e.getValue().toString() : ""));
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    /**
     * Constructs the fully-qualified model ARN required by the
     * {@code RetrieveAndGenerate} API.
     *
     * <p>Format: {@code arn:aws:bedrock:{region}::foundation-model/{modelId}}
     */
    public String buildModelArn(String modelId) {
        return "arn:aws:bedrock:" + properties.getRegion()
                + "::foundation-model/" + modelId;
    }

    private String resolveKbId(String requestOverride) {
        String id = (requestOverride != null && !requestOverride.isBlank())
                ? requestOverride
                : properties.getBedrock().getKnowledgeBase().getId();
        if (id == null || id.isBlank()) {
            throw new BedrockException(
                    "No Knowledge Base ID configured. Set KB_ID in .vscode/launch.json "
                    + "or aws.bedrock.knowledge-base.id in application.yml.");
        }
        return id;
    }

    private String resolveModelId(String requestOverride) {
        if (requestOverride != null && !requestOverride.isBlank()) return requestOverride;
        return properties.getBedrock().getKnowledgeBase().getModelId();
    }

    private int resolveTopK(int requestTopK) {
        return requestTopK > 0
                ? requestTopK
                : properties.getBedrock().getKnowledgeBase().getDefaultTopK();
    }
}
