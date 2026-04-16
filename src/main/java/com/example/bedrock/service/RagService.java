package com.example.bedrock.service;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates a DIY Retrieval-Augmented Generation (RAG) pipeline on top of
 * Amazon Bedrock — no managed knowledge base required.
 *
 * <h2>Pipeline overview</h2>
 * <pre>
 *  INGEST                           QUERY
 *  ──────                           ─────
 *  documents                        question
 *    │                                │
 *    ▼                                ▼
 *  chunk (word-based overlap)       embedText()
 *    │                                │
 *    ▼                                ▼
 *  embedText() per chunk            DocumentStore.findSimilar()
 *    │                                │
 *    ▼                                ▼
 *  DocumentStore.save()             build grounded prompt
 *                                     │
 *                                     ▼
 *                                   Bedrock Converse API
 *                                     │
 *                                     ▼
 *                                   RagQueryResponse (answer + sources)
 * </pre>
 *
 * <h2>Chunking strategy</h2>
 * <p>Text is split into overlapping word windows. A chunk of {@code chunkSize=200}
 * words with {@code chunkOverlap=20} means each successive chunk starts 180 words
 * after the previous one, sharing 20 words of context at both boundaries.
 *
 * <h2>Grounding prompt</h2>
 * <p>The system prompt instructs the model to answer <em>only</em> from the
 * retrieved context, citing which documents were used, and to reply
 * "I don't know" if the answer is not in the context — reducing hallucination.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final BedrockRuntimeClient bedrockClient;
    private final BedrockProperties    properties;
    private final EmbeddingService     embeddingService;
    private final DocumentStore        documentStore;

    // ── Ingest ────────────────────────────────────────────────────────────────

    /**
     * Ingests one or more documents: chunks them, embeds each chunk, and
     * persists everything in the in-memory {@link DocumentStore}.
     *
     * @param request batch of documents with optional chunking parameters
     * @return IDs assigned to each ingested document, plus chunk stats
     */
    public IngestResponse ingest(IngestRequest request) {
        int chunkSize    = request.getChunkSize();
        int chunkOverlap = request.getChunkOverlap();

        log.info("Ingesting {} document(s) — chunkSize={}, chunkOverlap={}",
                request.getDocuments().size(), chunkSize, chunkOverlap);

        List<String> documentIds = new ArrayList<>();
        int totalChunks = 0;

        for (IngestRequest.DocumentInput doc : request.getDocuments()) {
            List<String> chunkTexts = chunkText(doc.getContent(), chunkSize, chunkOverlap);
            log.debug("Document '{}' → {} chunks", doc.getTitle(), chunkTexts.size());

            List<DocumentStore.Chunk> chunks = new ArrayList<>();
            for (int i = 0; i < chunkTexts.size(); i++) {
                List<Double> embedding = embeddingService.embedText(chunkTexts.get(i));
                chunks.add(new DocumentStore.Chunk(chunkTexts.get(i), embedding, i));
            }

            String id = documentStore.save(doc.getTitle(), doc.getContent(), chunks);
            documentIds.add(id);
            totalChunks += chunks.size();
        }

        log.info("Ingest complete — {} documents, {} total chunks", documentIds.size(), totalChunks);

        return IngestResponse.builder()
                .documentIds(documentIds)
                .ingestedDocuments(documentIds.size())
                .totalChunks(totalChunks)
                .embeddingModel(properties.getBedrock().getEmbedding().getModelId())
                .timestamp(Instant.now())
                .build();
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * Answers a question using the documents in the store.
     *
     * <ol>
     *   <li>Embeds the question.</li>
     *   <li>Retrieves the top-K most relevant chunks from the store.</li>
     *   <li>Builds a grounding prompt with the retrieved context.</li>
     *   <li>Calls the Bedrock Converse API for answer generation.</li>
     * </ol>
     *
     * @param request question and optional retrieval / generation overrides
     * @return the model's answer, grounded in the retrieved context, plus source chunks
     */
    public RagQueryResponse query(RagQueryRequest request) {
        String generationModelId = resolveGenerationModelId(request.getModelId());
        String embeddingModelId  = properties.getBedrock().getEmbedding().getModelId();

        log.info("RAG query — question='{}', topK={}, minScore={}, model={}",
                request.getQuestion(), request.getTopK(), request.getMinScore(), generationModelId);

        // ── 1. Embed the question ─────────────────────────────────────────────
        List<Double> queryEmbedding = embeddingService.embedText(request.getQuestion());

        // ── 2. Retrieve relevant chunks ───────────────────────────────────────
        List<DocumentStore.ChunkMatch> matches = documentStore.findSimilar(
                queryEmbedding,
                request.getTopK(),
                request.getMinScore(),
                embeddingService::similarity);

        log.debug("Retrieved {} chunk(s) above minScore={}", matches.size(), request.getMinScore());

        // ── 3. Build grounded prompt ──────────────────────────────────────────
        String systemPrompt = buildSystemPrompt(request.getSystemPrompt(), matches);
        String userMessage  = request.getQuestion();

        // ── 4. Call Bedrock Converse API ──────────────────────────────────────
        ConverseRequest converseRequest = ConverseRequest.builder()
                .modelId(generationModelId)
                .system(SystemContentBlock.builder().text(systemPrompt).build())
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userMessage))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(properties.getBedrock().getMaxTokens())
                        .temperature(0.2f)   // low temperature for factual grounded answers
                        .build())
                .build();

        ConverseResponse converseResponse;
        try {
            converseResponse = bedrockClient.converse(converseRequest);
        } catch (Exception ex) {
            throw new BedrockException("RAG generation failed: " + ex.getMessage(), ex);
        }

        // ── 5. Extract answer ─────────────────────────────────────────────────
        String answer = converseResponse.output()
                .message()
                .content()
                .stream()
                .filter(b -> b.text() != null)
                .map(ContentBlock::text)
                .findFirst()
                .orElseThrow(() -> new BedrockException("Model returned an empty response"));

        // ── 6. Map sources ────────────────────────────────────────────────────
        List<RagQueryResponse.SourceChunk> sources = matches.stream()
                .map(m -> RagQueryResponse.SourceChunk.builder()
                        .documentId(m.documentId())
                        .title(m.title())
                        .chunkText(m.chunkText())
                        .score(m.score())
                        .chunkIndex(m.chunkIndex())
                        .build())
                .toList();

        // ── 7. Map token usage ────────────────────────────────────────────────
        TokenUsage usage = converseResponse.usage();
        ChatResponse.TokenUsage tokenUsage = ChatResponse.TokenUsage.builder()
                .inputTokens(usage != null ? usage.inputTokens()   : 0)
                .outputTokens(usage != null ? usage.outputTokens() : 0)
                .totalTokens(usage != null ? usage.totalTokens()   : 0)
                .build();

        log.info("RAG answer generated — {} sources, {} tokens total",
                sources.size(), tokenUsage.getTotalTokens());

        return RagQueryResponse.builder()
                .question(request.getQuestion())
                .answer(answer)
                .sources(sources)
                .retrievedChunks(sources.size())
                .generationModelId(generationModelId)
                .embeddingModelId(embeddingModelId)
                .usage(tokenUsage)
                .timestamp(Instant.now())
                .build();
    }

    // ── Chunking ──────────────────────────────────────────────────────────────

    /**
     * Splits {@code text} into overlapping word windows.
     *
     * <p>Each chunk contains at most {@code chunkSize} words. Consecutive chunks
     * overlap by {@code chunkOverlap} words so context is not lost at boundaries.
     * If {@code chunkOverlap >= chunkSize} the overlap is silently clamped to
     * {@code chunkSize - 1} to avoid an infinite loop.
     *
     * @param text        full document text
     * @param chunkSize   target words per chunk
     * @param chunkOverlap words shared between consecutive chunks
     * @return ordered list of chunk strings
     */
    List<String> chunkText(String text, int chunkSize, int chunkOverlap) {
        if (text == null || text.isBlank()) return List.of();

        String[] words = text.trim().split("\\s+");
        if (words.length == 0) return List.of();

        // Clamp overlap so step is always positive
        int overlap = Math.min(chunkOverlap, chunkSize - 1);
        int step    = chunkSize - overlap;

        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(start + chunkSize, words.length);
            chunks.add(String.join(" ", List.of(words).subList(start, end)));
            if (end == words.length) break;
        }
        return chunks;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the system prompt injected into the generation call.
     *
     * <p>If the caller supplied a custom system prompt it is used verbatim;
     * otherwise a default grounding prompt is generated that includes all
     * retrieved chunk texts as numbered context blocks.
     */
    private String buildSystemPrompt(String customPrompt,
                                     List<DocumentStore.ChunkMatch> matches) {
        if (customPrompt != null && !customPrompt.isBlank()) {
            return customPrompt + "\n\n" + buildContextBlock(matches);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful assistant that answers questions based strictly on the provided context.\n");
        sb.append("Rules:\n");
        sb.append("- Answer using ONLY information from the context below.\n");
        sb.append("- If the answer is not in the context, say \"I don't know based on the provided documents.\"\n");
        sb.append("- Do not make up facts or use external knowledge.\n");
        sb.append("- When relevant, mention which document(s) your answer is based on.\n\n");
        sb.append(buildContextBlock(matches));
        return sb.toString();
    }

    /**
     * Formats retrieved chunks as a numbered context block for the prompt.
     */
    private String buildContextBlock(List<DocumentStore.ChunkMatch> matches) {
        if (matches.isEmpty()) {
            return "CONTEXT: (no relevant documents found in the knowledge base)";
        }

        StringBuilder sb = new StringBuilder("CONTEXT:\n");
        for (int i = 0; i < matches.size(); i++) {
            DocumentStore.ChunkMatch m = matches.get(i);
            sb.append(String.format("[%d] Source: \"%s\" (relevance: %.2f)%n%s%n%n",
                    i + 1, m.title(), m.score(), m.chunkText()));
        }
        return sb.toString().trim();
    }

    private String resolveGenerationModelId(String requestOverride) {
        if (requestOverride != null && !requestOverride.isBlank()) return requestOverride;
        return properties.getBedrock().getModelId();
    }
}
