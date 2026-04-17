package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/code/fix}.
 *
 * <pre>{@code
 * {
 *   "fixedCode": "public int divide(int a, int b) {\n    if (b == 0) throw new ArithmeticException(\"Division by zero\");\n    return a / b;\n}",
 *   "language": "Java",
 *   "explanation": "Added a guard clause to prevent division by zero before performing the operation.",
 *   "changes": [
 *     "Added null/zero guard for parameter b (line 2)",
 *     "Throws descriptive ArithmeticException instead of crashing silently"
 *   ],
 *   "modelId": "amazon.nova-lite-v1:0",
 *   "usage": { "inputTokens": 95, "outputTokens": 145, "totalTokens": 240 },
 *   "timestamp": "2025-04-17T09:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeFixResponse {

    /** The corrected code with all identified bugs resolved. */
    private String fixedCode;

    /** Detected or provided programming language. */
    private String language;

    /** Human-readable explanation of what was wrong and how it was fixed. */
    private String explanation;

    /**
     * Itemised list of every change made — one entry per fix.
     * Useful for code review or audit trail.
     */
    private List<String> changes;

    /** The Bedrock model used for fixing. */
    private String modelId;

    /** Token usage for the fix call. */
    private ChatResponse.TokenUsage usage;

    /** Server-side timestamp. */
    private Instant timestamp;
}
