package com.example.bedrock.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response body for {@code POST /api/analysis/analyze}.
 *
 * <p>Each analysis section is {@code null} when the corresponding
 * {@link AnalysisType} was not requested. Inspect {@code analysisTypes}
 * to see which sections were actually computed.
 *
 * <pre>{@code
 * {
 *   "analysisTypes": ["SENTIMENT", "ENTITIES", "KEY_PHRASES", "CLASSIFICATION", "LANGUAGE_DETECTION"],
 *   "sentiment": { "label": "POSITIVE", "confidence": 0.92, ... },
 *   "entities":  [{ "text": "Apple Inc.", "type": "ORGANIZATION", "confidence": 0.98 }],
 *   "keyPhrases": [{ "phrase": "record revenue", "score": 0.95 }],
 *   "classifications": [{ "label": "TECHNOLOGY", "score": 0.87 }],
 *   "language": { "languageCode": "en", "languageName": "English", "confidence": 0.99 },
 *   "modelId": "amazon.nova-lite-v1:0",
 *   "usage":   { "inputTokens": 540, "outputTokens": 210, "totalTokens": 750 },
 *   "timestamp": "2025-04-17T09:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResponse {

    /** Which analyses were requested and are present in this response. */
    private List<AnalysisType> analysisTypes;

    /** Sentiment analysis result. {@code null} if {@code SENTIMENT} was not requested. */
    private SentimentResult sentiment;

    /** Named entities found in the text. {@code null} if {@code ENTITIES} was not requested. */
    private List<EntityResult> entities;

    /** Key phrases extracted from the text. {@code null} if {@code KEY_PHRASES} was not requested. */
    private List<KeyPhraseResult> keyPhrases;

    /**
     * Text classification results, ranked by score descending.
     * The first element is the top predicted category.
     * {@code null} if {@code CLASSIFICATION} was not requested.
     */
    private List<ClassificationResult> classifications;

    /** Detected language. {@code null} if {@code LANGUAGE_DETECTION} was not requested. */
    private LanguageResult language;

    /** The Bedrock model used for analysis. */
    private String modelId;

    /** Token usage for the analysis call. */
    private ChatResponse.TokenUsage usage;

    /** Server-side timestamp of when the analysis was performed. */
    private Instant timestamp;

    // ── Result types ──────────────────────────────────────────────────────────

    /**
     * Sentiment analysis result.
     *
     * <p>{@code label} holds the dominant sentiment; the four score fields sum to 1.0.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SentimentResult {

        /**
         * Dominant sentiment: {@code POSITIVE}, {@code NEGATIVE},
         * {@code NEUTRAL}, or {@code MIXED}.
         */
        private String label;

        /** Confidence in the dominant label (0–1). */
        private double confidence;

        /** Share of positive signal (0–1). */
        private double positiveScore;

        /** Share of negative signal (0–1). */
        private double negativeScore;

        /** Share of neutral signal (0–1). */
        private double neutralScore;

        /** Share of mixed (conflicting) signal (0–1). */
        private double mixedScore;
    }

    /**
     * A single named entity detected in the text.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EntityResult {

        /** Exact span of text that was identified as an entity. */
        private String text;

        /**
         * Entity category — one of:
         * {@code PERSON}, {@code ORGANIZATION}, {@code LOCATION},
         * {@code DATE}, {@code TIME}, {@code MONEY}, {@code PERCENTAGE},
         * {@code PRODUCT}, {@code EVENT}, {@code OTHER}.
         */
        private String type;

        /** Model confidence for this entity (0–1). */
        private double confidence;
    }

    /**
     * A key phrase extracted from the text.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeyPhraseResult {

        /** The key phrase text. */
        private String phrase;

        /** Relevance score (0–1). */
        private double score;
    }

    /**
     * A single text classification result.
     *
     * <p>The response contains a ranked list — first element is the top prediction.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClassificationResult {

        /** Category label (built-in or custom). */
        private String label;

        /** Confidence for this label (0–1). */
        private double score;
    }

    /**
     * Language detection result.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LanguageResult {

        /** BCP-47 language code (e.g. {@code "en"}, {@code "fr"}, {@code "de"}). */
        private String languageCode;

        /** Full English name of the language (e.g. {@code "English"}, {@code "French"}). */
        private String languageName;

        /** Confidence in the detection (0–1). */
        private double confidence;
    }

    // ── Internal Jackson target (model JSON → service) ────────────────────────

    /**
     * Internal POJO that Jackson deserialises the raw model JSON into.
     * The service then maps this into the public {@link AnalysisResponse}.
     *
     * <p>All fields are nullable so a partial model response does not fail parsing.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelOutput {
        private SentimentResult sentiment;
        private List<EntityResult> entities;
        private List<KeyPhraseResult> keyPhrases;
        private List<ClassificationResult> classifications;
        private LanguageResult language;
        private Map<String, Object> raw; // catch-all for unexpected keys
    }
}
