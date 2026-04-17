package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/code/convert}.
 *
 * <h2>Python → TypeScript</h2>
 * <pre>{@code
 * {
 *   "code": "def add(a, b):\n    return a + b",
 *   "targetLanguage": "TypeScript"
 * }
 * }</pre>
 *
 * <h2>Java → Go with explicit source language</h2>
 * <pre>{@code
 * {
 *   "code": "public int[] twoSum(int[] nums, int target) { ... }",
 *   "sourceLanguage": "Java",
 *   "targetLanguage": "Go"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeConvertRequest {

    /** The source code to convert (max 100 000 chars). */
    @NotBlank(message = "code must not be blank")
    @Size(max = 100_000, message = "code must not exceed 100,000 characters")
    private String code;

    /**
     * Source programming language. When {@code null} the model auto-detects it.
     * Providing it explicitly improves conversion accuracy for ambiguous snippets.
     */
    @Size(max = 50, message = "sourceLanguage must not exceed 50 characters")
    private String sourceLanguage;

    /**
     * Target programming language to convert the code into
     * (e.g. {@code "Python"}, {@code "TypeScript"}, {@code "Go"}, {@code "Rust"}).
     */
    @NotBlank(message = "targetLanguage must not be blank")
    @Size(max = 50, message = "targetLanguage must not exceed 50 characters")
    private String targetLanguage;

    /** Override the Bedrock model. Defaults to {@code aws.bedrock.model-id}. */
    private String modelId;
}
