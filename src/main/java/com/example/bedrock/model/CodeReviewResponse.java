package com.example.bedrock.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/code/review}.
 *
 * <pre>{@code
 * {
 *   "issues": [
 *     {
 *       "severity": "CRITICAL",
 *       "category": "SECURITY",
 *       "description": "SQL injection vulnerability — user input is concatenated directly into the query.",
 *       "lineReference": "line 3",
 *       "suggestion": "Use parameterized queries or a prepared statement."
 *     }
 *   ],
 *   "summary": "The code has a critical SQL injection vulnerability that must be fixed before production.",
 *   "overallRating": 3,
 *   "modelId": "amazon.nova-lite-v1:0",
 *   "usage": { "inputTokens": 140, "outputTokens": 210, "totalTokens": 350 },
 *   "timestamp": "2025-04-17T09:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewResponse {

    /**
     * List of issues found, ordered by severity (CRITICAL first).
     * Empty list means no issues were found.
     */
    private List<ReviewIssue> issues;

    /** High-level summary of the overall code quality and the most important findings. */
    private String summary;

    /**
     * Overall code quality rating from 1 (very poor) to 10 (excellent).
     * Based on the severity and count of issues found.
     */
    private int overallRating;

    /** The Bedrock model used for the review. */
    private String modelId;

    /** Token usage for the review call. */
    private ChatResponse.TokenUsage usage;

    /** Server-side timestamp. */
    private Instant timestamp;

    // ── Issue type ────────────────────────────────────────────────────────────

    /**
     * A single code issue identified during review.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReviewIssue {

        /**
         * Issue severity:
         * {@code CRITICAL} → must fix; {@code HIGH} → should fix;
         * {@code MEDIUM} → recommended; {@code LOW} → minor; {@code INFO} → suggestion only.
         */
        private String severity;

        /**
         * Issue category: {@code BUG}, {@code SECURITY}, {@code PERFORMANCE},
         * {@code STYLE}, or {@code MAINTAINABILITY}.
         */
        private String category;

        /** Clear description of the issue and why it matters. */
        private String description;

        /**
         * Approximate location in the code (e.g. {@code "line 12"},
         * {@code "lines 8–15"}, {@code "getUserById method"}).
         * {@code null} when the issue applies to the whole snippet.
         */
        private String lineReference;

        /** Concrete suggestion for how to fix or improve the code. */
        private String suggestion;
    }

    // ── Internal Jackson target ───────────────────────────────────────────────

    /**
     * Internal POJO that Jackson deserialises the raw model JSON into.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelOutput {
        private List<ReviewIssue> issues;
        private String summary;
        private int overallRating;
    }
}
