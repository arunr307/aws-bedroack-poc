package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/code/fix}.
 *
 * <h2>Fix with error message</h2>
 * <pre>{@code
 * {
 *   "code": "public int divide(int a, int b) { return a / b; }",
 *   "language": "Java",
 *   "errorMessage": "ArithmeticException: / by zero"
 * }
 * }</pre>
 *
 * <h2>Fix without specific error (general debugging)</h2>
 * <pre>{@code
 * {
 *   "code": "def fibonacci(n):\n    if n == 0: return 0\n    return fibonacci(n-1) + fibonacci(n-2)",
 *   "language": "Python"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeFixRequest {

    /** The buggy or incorrect code to fix (max 100 000 chars). */
    @NotBlank(message = "code must not be blank")
    @Size(max = 100_000, message = "code must not exceed 100,000 characters")
    private String code;

    /**
     * Programming language hint. Auto-detected when {@code null}.
     * Specifying it helps the model apply language-specific fixes.
     */
    @Size(max = 50, message = "language must not exceed 50 characters")
    private String language;

    /**
     * The error message, exception, or description of the observed wrong behaviour.
     * Examples: {@code "NullPointerException at line 12"},
     * {@code "Returns wrong result for negative inputs"},
     * {@code "Infinite loop when list is empty"}.
     * When {@code null}, the model performs a general correctness review and fixes
     * any issues it finds.
     */
    @Size(max = 5_000, message = "errorMessage must not exceed 5,000 characters")
    private String errorMessage;

    /** Override the Bedrock model. Defaults to {@code aws.bedrock.model-id}. */
    private String modelId;
}
