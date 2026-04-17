package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/code/convert}.
 *
 * <pre>{@code
 * {
 *   "convertedCode": "def add(a: int, b: int) -> int:\n    return a + b",
 *   "sourceLanguage": "Java",
 *   "targetLanguage": "Python",
 *   "notes": [
 *     "Removed explicit type declarations — Python uses duck typing",
 *     "Added PEP 484 type hints for clarity",
 *     "Removed semicolons and braces — Python uses indentation"
 *   ],
 *   "modelId": "amazon.nova-lite-v1:0",
 *   "usage": { "inputTokens": 90, "outputTokens": 130, "totalTokens": 220 },
 *   "timestamp": "2025-04-17T09:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeConvertResponse {

    /** The converted code in the target language. */
    private String convertedCode;

    /** Detected or provided source language. */
    private String sourceLanguage;

    /** The target language the code was converted to. */
    private String targetLanguage;

    /**
     * Notable observations about the conversion — idiom differences, removed constructs,
     * required package changes, or behavioural caveats.
     */
    private List<String> notes;

    /** The Bedrock model used for conversion. */
    private String modelId;

    /** Token usage for the conversion call. */
    private ChatResponse.TokenUsage usage;

    /** Server-side timestamp. */
    private Instant timestamp;
}
