package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for {@code POST /api/analysis/analyze}.
 *
 * <h2>Run all analyses (default)</h2>
 * <pre>{@code
 * { "text": "Apple Inc. reported record revenue of $89.5B in Q1 2024..." }
 * }</pre>
 *
 * <h2>Run specific analyses only</h2>
 * <pre>{@code
 * {
 *   "text": "...",
 *   "analysisTypes": ["SENTIMENT", "ENTITIES"]
 * }
 * }</pre>
 *
 * <h2>Custom classification labels</h2>
 * <pre>{@code
 * {
 *   "text": "...",
 *   "analysisTypes": ["CLASSIFICATION"],
 *   "customLabels": ["Bug Report", "Feature Request", "General Inquiry", "Billing"]
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisRequest {

    /**
     * The document text to analyse.
     * For best results, provide at least a few sentences.
     * Very short texts (< 10 words) may yield low-confidence results.
     */
    @NotBlank(message = "text must not be blank")
    @Size(max = 100_000, message = "text must not exceed 100,000 characters")
    private String text;

    /**
     * Which analyses to run. When {@code null} or empty, all five analyses are run.
     * Selectively requesting only what you need reduces latency and token cost.
     */
    private List<AnalysisType> analysisTypes;

    /**
     * Custom category labels for the {@link AnalysisType#CLASSIFICATION} analysis.
     * When provided, the model classifies the text into these categories instead of
     * the built-in taxonomy (TECHNOLOGY, BUSINESS, HEALTH, SCIENCE, POLITICS,
     * SPORTS, ENTERTAINMENT, FINANCE, LEGAL, OTHER).
     * Maximum 30 labels.
     */
    @Size(max = 30, message = "maximum 30 custom classification labels")
    private List<String> customLabels;

    /**
     * Override the Bedrock model used for analysis.
     * Defaults to {@code aws.bedrock.model-id} in {@code application.yml}.
     */
    private String modelId;
}
