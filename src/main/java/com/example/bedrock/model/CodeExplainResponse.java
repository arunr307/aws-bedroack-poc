package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/code/explain}.
 *
 * <pre>{@code
 * {
 *   "language": "Java",
 *   "explanation": "This method computes Fibonacci numbers recursively...",
 *   "keyPoints": [
 *     "Uses naive recursion — exponential time complexity O(2^n)",
 *     "Base cases: n=0 returns 0, n=1 returns 1",
 *     "No memoization — recomputes sub-problems repeatedly"
 *   ],
 *   "complexity": "MODERATE",
 *   "modelId": "amazon.nova-lite-v1:0",
 *   "usage": { "inputTokens": 85, "outputTokens": 180, "totalTokens": 265 },
 *   "timestamp": "2025-04-17T09:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeExplainResponse {

    /**
     * Detected or provided programming language.
     * Auto-detected when the request did not specify a language.
     */
    private String language;

    /**
     * Full explanation of the code.
     * Length depends on {@link DetailLevel} — brief summary vs. line-by-line walkthrough.
     */
    private String explanation;

    /**
     * Bullet-point highlights: notable patterns, pitfalls, algorithmic complexity, etc.
     * Most useful when {@link DetailLevel#DETAILED} is requested.
     */
    private List<String> keyPoints;

    /**
     * Estimated code complexity: {@code SIMPLE}, {@code MODERATE}, or {@code COMPLEX}.
     * Based on cyclomatic complexity, nesting depth, and algorithm sophistication.
     */
    private String complexity;

    /** The Bedrock model used for the explanation. */
    private String modelId;

    /** Token usage for the explanation call. */
    private ChatResponse.TokenUsage usage;

    /** Server-side timestamp. */
    private Instant timestamp;
}
