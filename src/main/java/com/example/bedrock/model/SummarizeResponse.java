package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response body returned by {@code POST /api/summarize}.
 *
 * <pre>{@code
 * {
 *   "summary": "AWS Bedrock is a fully managed service...",
 *   "style": "BRIEF",
 *   "originalWordCount": 1250,
 *   "summaryWordCount": 42,
 *   "compressionRatio": 29.8,
 *   "modelId": "amazon.nova-lite-v1:0",
 *   "usage": { "inputTokens": 1380, "outputTokens": 55, "totalTokens": 1435 },
 *   "timestamp": "2025-04-16T22:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummarizeResponse {

    /** The generated summary text. */
    private String summary;

    /** The summarization style that was applied. */
    private SummarizeRequest.SummaryStyle style;

    /** Approximate word count of the original input text. */
    private int originalWordCount;

    /** Approximate word count of the generated summary. */
    private int summaryWordCount;

    /**
     * Compression ratio: {@code originalWordCount / summaryWordCount}.
     * A value of {@code 10.0} means the summary is 10× shorter than the original.
     */
    private double compressionRatio;

    /** The Bedrock model that produced this summary. */
    private String modelId;

    /** Token usage for billing and monitoring. */
    private ChatResponse.TokenUsage usage;

    /** Server-side timestamp when the summary was generated. */
    private Instant timestamp;
}
