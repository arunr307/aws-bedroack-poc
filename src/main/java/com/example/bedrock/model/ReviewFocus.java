package com.example.bedrock.model;

/**
 * Aspect of code to focus on during a review
 * in {@code POST /api/code/review}.
 */
public enum ReviewFocus {

    /** Logic errors, null pointer risks, off-by-one errors, exception handling. */
    BUGS("Logic errors, null-safety issues, off-by-one errors, exception handling"),

    /** Injection flaws, insecure defaults, sensitive data exposure, auth weaknesses. */
    SECURITY("SQL/command injection, insecure defaults, sensitive data exposure, auth weaknesses"),

    /** Algorithmic complexity, unnecessary allocations, blocking I/O, caching opportunities. */
    PERFORMANCE("Time/space complexity, unnecessary allocations, blocking I/O, caching"),

    /** Naming conventions, formatting, dead code, magic numbers, code duplication. */
    STYLE("Naming conventions, formatting, dead code, magic numbers, code duplication"),

    /** Readability, testability, SOLID principles, coupling, abstraction quality. */
    MAINTAINABILITY("Readability, testability, SOLID principles, coupling, abstraction quality");

    private final String description;

    ReviewFocus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
