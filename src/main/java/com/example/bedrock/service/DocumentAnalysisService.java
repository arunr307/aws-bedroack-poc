package com.example.bedrock.service;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs multi-dimensional document analysis using a single Amazon Bedrock
 * Converse API call.
 *
 * <h2>Analyses supported</h2>
 * <ul>
 *   <li><strong>Sentiment</strong> — dominant label (POSITIVE/NEGATIVE/NEUTRAL/MIXED)
 *       with per-class confidence scores</li>
 *   <li><strong>Entities</strong> — named entities (PERSON, ORGANIZATION, LOCATION,
 *       DATE, MONEY, PRODUCT, EVENT, …)</li>
 *   <li><strong>Key phrases</strong> — most important phrases ranked by relevance</li>
 *   <li><strong>Classification</strong> — built-in taxonomy or user-supplied labels</li>
 *   <li><strong>Language detection</strong> — BCP-47 code, full name, confidence</li>
 * </ul>
 *
 * <h2>Implementation strategy</h2>
 * <p>All requested analyses are packed into a <em>single</em> Bedrock call.
 * The system prompt instructs the model to return <strong>only</strong> valid JSON —
 * no prose, no markdown fences. The service then strips any accidental fences,
 * parses the JSON with Jackson, and assembles the typed {@link AnalysisResponse}.
 *
 * <h2>Why a single call?</h2>
 * <p>The model reads the document once. One call = lower latency, fewer tokens,
 * and one billing event — versus N separate calls that each re-tokenise the document.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAnalysisService {

    /** Strips ` ```json ... ``` ` or ` ``` ... ``` ` wrappers the model sometimes emits. */
    private static final Pattern FENCE_PATTERN =
            Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");

    /**
     * Default classification categories used when the caller does not supply
     * {@code customLabels}.
     */
    private static final List<String> DEFAULT_LABELS = List.of(
            "TECHNOLOGY", "BUSINESS", "HEALTH", "SCIENCE",
            "POLITICS", "SPORTS", "ENTERTAINMENT", "FINANCE", "LEGAL", "OTHER");

    private final BedrockRuntimeClient bedrockClient;
    private final BedrockProperties    properties;
    private final ObjectMapper         objectMapper;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Analyses a document and returns the requested insights.
     *
     * @param request text + optional list of analysis types + optional custom labels
     * @return typed analysis results, plus token usage and metadata
     */
    public AnalysisResponse analyze(AnalysisRequest request) {
        List<AnalysisType> types = resolveTypes(request.getAnalysisTypes());
        String             modelId = resolveModelId(request.getModelId());

        log.info("Analysing document — types={}, length={}, model={}",
                types, request.getText().length(), modelId);

        // ── 1. Build the prompt ────────────────────────────────────────────────
        String systemPrompt = buildSystemPrompt();
        String userMessage  = buildUserMessage(request.getText(), types, request.getCustomLabels());

        // ── 2. Call Bedrock (low temperature for deterministic structured output)
        ConverseRequest converseRequest = ConverseRequest.builder()
                .modelId(modelId)
                .system(SystemContentBlock.builder().text(systemPrompt).build())
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userMessage))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(4096)   // entity lists can be long
                        .temperature(0.0f) // deterministic JSON
                        .build())
                .build();

        ConverseResponse converseResponse;
        try {
            converseResponse = bedrockClient.converse(converseRequest);
        } catch (Exception ex) {
            throw new BedrockException("Document analysis call failed: " + ex.getMessage(), ex);
        }

        // ── 3. Extract the raw text from the response ─────────────────────────
        String rawText = converseResponse.output()
                .message()
                .content()
                .stream()
                .filter(b -> b.text() != null)
                .map(ContentBlock::text)
                .findFirst()
                .orElseThrow(() -> new BedrockException("Model returned an empty response"));

        log.debug("Raw model output ({} chars): {}", rawText.length(),
                rawText.length() > 200 ? rawText.substring(0, 200) + "…" : rawText);

        // ── 4. Parse JSON ─────────────────────────────────────────────────────
        AnalysisResponse.ModelOutput output = parseModelOutput(rawText);

        // ── 5. Map token usage ────────────────────────────────────────────────
        TokenUsage sdkUsage = converseResponse.usage();
        ChatResponse.TokenUsage usage = ChatResponse.TokenUsage.builder()
                .inputTokens(sdkUsage  != null ? sdkUsage.inputTokens()   : 0)
                .outputTokens(sdkUsage != null ? sdkUsage.outputTokens()  : 0)
                .totalTokens(sdkUsage  != null ? sdkUsage.totalTokens()   : 0)
                .build();

        log.info("Analysis complete — types={}, tokens={}", types, usage.getTotalTokens());

        // ── 6. Assemble the response, including only requested sections ────────
        return AnalysisResponse.builder()
                .analysisTypes(types)
                .sentiment(types.contains(AnalysisType.SENTIMENT)
                        ? output.getSentiment() : null)
                .entities(types.contains(AnalysisType.ENTITIES)
                        ? output.getEntities() : null)
                .keyPhrases(types.contains(AnalysisType.KEY_PHRASES)
                        ? output.getKeyPhrases() : null)
                .classifications(types.contains(AnalysisType.CLASSIFICATION)
                        ? output.getClassifications() : null)
                .language(types.contains(AnalysisType.LANGUAGE_DETECTION)
                        ? output.getLanguage() : null)
                .modelId(modelId)
                .usage(usage)
                .timestamp(Instant.now())
                .build();
    }

    // ── Prompt construction ───────────────────────────────────────────────────

    /**
     * Fixed system prompt that strictly instructs the model to emit only JSON.
     */
    private String buildSystemPrompt() {
        return """
                You are a document analysis engine.
                You respond ONLY with a valid JSON object — no markdown fences, no prose, no explanation.
                Every key in the JSON must match exactly the schema described in the user message.
                If you cannot determine a value, use null for objects or an empty array for lists.
                Never include commentary outside the JSON object.
                """;
    }

    /**
     * Builds the user message dynamically — only includes schema sections for
     * the analyses that were actually requested, keeping the prompt concise.
     */
    private String buildUserMessage(String text,
                                    List<AnalysisType> types,
                                    List<String> customLabels) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze the document below and return a JSON object with exactly these keys ");
        sb.append("(include only the keys listed here):\n\n");

        if (types.contains(AnalysisType.SENTIMENT)) {
            sb.append("""
                    "sentiment": {
                      "label": one of POSITIVE | NEGATIVE | NEUTRAL | MIXED,
                      "confidence": float 0-1 (confidence in the dominant label),
                      "positiveScore": float 0-1,
                      "negativeScore": float 0-1,
                      "neutralScore":  float 0-1,
                      "mixedScore":    float 0-1
                    }
                    (The four score fields should roughly sum to 1.0)

                    """);
        }

        if (types.contains(AnalysisType.ENTITIES)) {
            sb.append("""
                    "entities": [
                      {
                        "text":       the exact text span,
                        "type":       one of PERSON | ORGANIZATION | LOCATION | DATE | TIME | MONEY | PERCENTAGE | PRODUCT | EVENT | OTHER,
                        "confidence": float 0-1
                      }
                    ]
                    (Return every distinct entity found; if none, return an empty array)

                    """);
        }

        if (types.contains(AnalysisType.KEY_PHRASES)) {
            sb.append("""
                    "keyPhrases": [
                      { "phrase": string, "score": float 0-1 }
                    ]
                    (Up to 15 phrases sorted by score descending; if none, return an empty array)

                    """);
        }

        if (types.contains(AnalysisType.CLASSIFICATION)) {
            List<String> labels = (customLabels != null && !customLabels.isEmpty())
                    ? customLabels : DEFAULT_LABELS;
            sb.append("\"classifications\": [\n");
            sb.append("  { \"label\": one of ")
              .append(String.join(" | ", labels))
              .append(", \"score\": float 0-1 }\n");
            sb.append("]\n");
            sb.append("(All matching categories sorted by score descending; ");
            sb.append("scores may not sum to 1.0 when multiple categories apply; ");
            sb.append("if none match, return an empty array)\n\n");
        }

        if (types.contains(AnalysisType.LANGUAGE_DETECTION)) {
            sb.append("""
                    "language": {
                      "languageCode": BCP-47 code (e.g. "en", "fr", "de", "es", "zh"),
                      "languageName": full English name (e.g. "English", "French"),
                      "confidence":   float 0-1
                    }

                    """);
        }

        sb.append("--- BEGIN DOCUMENT ---\n");
        sb.append(text.trim());
        sb.append("\n--- END DOCUMENT ---");
        return sb.toString();
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    /**
     * Strips markdown code fences from the model output (if present), then
     * deserialises the JSON into a {@link AnalysisResponse.ModelOutput}.
     *
     * <p>Two-stage fallback:
     * <ol>
     *   <li>Regex strip of {@code ```json ... ```} or {@code ``` ... ```}</li>
     *   <li>If still invalid, search for the first {@code {} and last {@code }} and
     *       substring between them.</li>
     * </ol>
     */
    public AnalysisResponse.ModelOutput parseModelOutput(String rawText) {
        String cleaned = stripFences(rawText);
        try {
            return objectMapper.readValue(cleaned, AnalysisResponse.ModelOutput.class);
        } catch (Exception firstEx) {
            // Second fallback: find outermost { ... }
            int start = cleaned.indexOf('{');
            int end   = cleaned.lastIndexOf('}');
            if (start != -1 && end > start) {
                try {
                    return objectMapper.readValue(
                            cleaned.substring(start, end + 1),
                            AnalysisResponse.ModelOutput.class);
                } catch (Exception secondEx) {
                    log.warn("JSON parsing failed even after brace extraction. Raw: {}", rawText);
                    throw new BedrockException(
                            "Model returned unparseable JSON: " + secondEx.getMessage(), secondEx);
                }
            }
            log.warn("No JSON object found in model output. Raw: {}", rawText);
            throw new BedrockException(
                    "Model returned unparseable JSON: " + firstEx.getMessage(), firstEx);
        }
    }

    /**
     * Removes ``` or ```json ... ``` fences that the model sometimes emits
     * despite being told not to.
     */
    private String stripFences(String text) {
        Matcher m = FENCE_PATTERN.matcher(text.trim());
        return m.find() ? m.group(1).trim() : text.trim();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * When the caller supplies no analysis types (null or empty), defaults to all.
     */
    private List<AnalysisType> resolveTypes(List<AnalysisType> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.copyOf(EnumSet.allOf(AnalysisType.class));
        }
        return requested;
    }

    private String resolveModelId(String requestOverride) {
        if (requestOverride != null && !requestOverride.isBlank()) return requestOverride;
        return properties.getBedrock().getModelId();
    }
}
