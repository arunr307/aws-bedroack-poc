package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for {@code POST /api/code/review}.
 *
 * <h2>Full review (default)</h2>
 * <pre>{@code
 * {
 *   "code": "public String getUserById(String id) { return db.query(\"SELECT * FROM users WHERE id = \" + id); }",
 *   "language": "Java"
 * }
 * }</pre>
 *
 * <h2>Security-only review</h2>
 * <pre>{@code
 * {
 *   "code": "...",
 *   "language": "Python",
 *   "focusAreas": ["SECURITY"]
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewRequest {

    /** The code to review (max 100 000 chars). */
    @NotBlank(message = "code must not be blank")
    @Size(max = 100_000, message = "code must not exceed 100,000 characters")
    private String code;

    /**
     * Programming language hint. Auto-detected when {@code null}.
     * Specifying it improves the accuracy of language-specific advice.
     */
    @Size(max = 50, message = "language must not exceed 50 characters")
    private String language;

    /**
     * Which aspects of the code to review.
     * When {@code null} or empty, all focus areas are reviewed.
     *
     * @see ReviewFocus
     */
    private List<ReviewFocus> focusAreas;

    /** Override the Bedrock model. Defaults to {@code aws.bedrock.model-id}. */
    private String modelId;
}
