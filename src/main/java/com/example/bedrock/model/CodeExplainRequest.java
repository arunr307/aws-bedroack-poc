package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/code/explain}.
 *
 * <h2>Brief explanation</h2>
 * <pre>{@code
 * {
 *   "code": "public int fib(int n) { return n <= 1 ? n : fib(n-1) + fib(n-2); }",
 *   "detailLevel": "BRIEF"
 * }
 * }</pre>
 *
 * <h2>Detailed explanation with explicit language</h2>
 * <pre>{@code
 * {
 *   "code": "SELECT u.name, COUNT(o.id) FROM users u LEFT JOIN orders o ON u.id = o.user_id GROUP BY u.id",
 *   "language": "SQL",
 *   "detailLevel": "DETAILED"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeExplainRequest {

    /** The code snippet to explain (max 100 000 chars). */
    @NotBlank(message = "code must not be blank")
    @Size(max = 100_000, message = "code must not exceed 100,000 characters")
    private String code;

    /**
     * Programming language hint. When {@code null} the model auto-detects the language.
     * Providing it improves accuracy for languages with ambiguous syntax.
     */
    @Size(max = 50, message = "language must not exceed 50 characters")
    private String language;

    /**
     * How detailed the explanation should be.
     * <ul>
     *   <li>{@code BRIEF} — 2–4 sentences summarising what the code does</li>
     *   <li>{@code DETAILED} — line-by-line walkthrough with key points and complexity analysis</li>
     * </ul>
     * Defaults to {@code DETAILED}.
     */
    @Builder.Default
    private DetailLevel detailLevel = DetailLevel.DETAILED;

    /** Override the Bedrock model. Defaults to {@code aws.bedrock.model-id}. */
    private String modelId;
}
