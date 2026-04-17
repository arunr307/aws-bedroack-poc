package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for {@code POST /api/code/generate}.
 *
 * <h2>Minimal</h2>
 * <pre>{@code
 * {
 *   "description": "A REST endpoint that accepts a list of integers and returns their sum",
 *   "language": "Java"
 * }
 * }</pre>
 *
 * <h2>With framework and extra requirements</h2>
 * <pre>{@code
 * {
 *   "description": "JWT authentication filter",
 *   "language": "Java",
 *   "framework": "Spring Boot",
 *   "requirements": ["Use io.jsonwebtoken library", "Validate expiry", "Add Javadoc"]
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeGenerateRequest {

    /**
     * Natural language description of what the code should do.
     * Be as specific as possible — include inputs, outputs, edge cases, and constraints.
     */
    @NotBlank(message = "description must not be blank")
    @Size(max = 10_000, message = "description must not exceed 10,000 characters")
    private String description;

    /**
     * Target programming language (e.g. {@code "Java"}, {@code "Python"},
     * {@code "TypeScript"}, {@code "Go"}, {@code "Rust"}).
     */
    @NotBlank(message = "language must not be blank")
    @Size(max = 50, message = "language must not exceed 50 characters")
    private String language;

    /**
     * Optional framework or library context
     * (e.g. {@code "Spring Boot"}, {@code "FastAPI"}, {@code "React"}).
     * When provided, the model generates idiomatic code for that framework.
     */
    @Size(max = 100, message = "framework must not exceed 100 characters")
    private String framework;

    /**
     * Additional constraints or requirements for the generated code.
     * Examples: {@code "include error handling"}, {@code "use async/await"},
     * {@code "add unit tests"}, {@code "follow SOLID principles"}.
     * Maximum 20 requirements.
     */
    @Size(max = 20, message = "maximum 20 requirements")
    private List<String> requirements;

    /**
     * Override the Bedrock model used for generation.
     * Defaults to {@code aws.bedrock.model-id} in {@code application.yml}.
     */
    private String modelId;
}
