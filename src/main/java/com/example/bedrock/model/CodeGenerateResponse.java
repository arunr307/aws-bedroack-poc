package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/code/generate}.
 *
 * <pre>{@code
 * {
 *   "code": "public int sum(List<Integer> numbers) {\n    return numbers.stream().mapToInt(Integer::intValue).sum();\n}",
 *   "language": "Java",
 *   "explanation": "Uses Java streams to sum a list of integers in a single functional pipeline.",
 *   "dependencies": ["java.util.List", "java.util.stream"],
 *   "modelId": "amazon.nova-lite-v1:0",
 *   "usage": { "inputTokens": 120, "outputTokens": 95, "totalTokens": 215 },
 *   "timestamp": "2025-04-17T09:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeGenerateResponse {

    /** The generated code, ready to copy-paste. */
    private String code;

    /** The programming language of the generated code. */
    private String language;

    /** Brief explanation of what the code does and any notable design decisions. */
    private String explanation;

    /**
     * External libraries or imports required to run the generated code.
     * Empty list when only standard-library constructs are used.
     */
    private List<String> dependencies;

    /** The Bedrock model used for generation. */
    private String modelId;

    /** Token usage for the generation call. */
    private ChatResponse.TokenUsage usage;

    /** Server-side timestamp. */
    private Instant timestamp;
}
