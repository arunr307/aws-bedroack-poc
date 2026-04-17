package com.example.bedrock.model;

/**
 * The set of analyses that {@code POST /api/analysis/analyze} can perform.
 *
 * <p>Pass one or more values in the {@code analysisTypes} field of
 * {@link AnalysisRequest}. Omit the field to run <em>all</em> analyses.
 */
public enum AnalysisType {

    /** Classify the overall emotional tone as POSITIVE, NEGATIVE, NEUTRAL, or MIXED. */
    SENTIMENT("Sentiment analysis — classifies overall tone and provides per-sentiment confidence scores"),

    /**
     * Extract named entities: people, organizations, locations, dates, money amounts,
     * products, events, and more.
     */
    ENTITIES("Named entity recognition — extracts people, organizations, locations, dates, money, products, and events"),

    /** Identify the most important phrases and topics from the text. */
    KEY_PHRASES("Key phrase extraction — surfaces the most meaningful phrases and topics"),

    /**
     * Assign the text to one or more categories.
     * Supply {@code customLabels} to override the built-in taxonomy.
     */
    CLASSIFICATION("Text classification — assigns the text to predefined or custom categories"),

    /** Detect the natural language the text is written in (BCP-47 code + full name). */
    LANGUAGE_DETECTION("Language detection — identifies the language and reports a confidence score");

    private final String description;

    AnalysisType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
