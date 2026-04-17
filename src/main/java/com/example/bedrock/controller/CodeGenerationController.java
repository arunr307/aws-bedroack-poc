package com.example.bedrock.controller;

import com.example.bedrock.model.*;
import com.example.bedrock.service.CodeGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * REST controller for code-intelligence operations powered by Amazon Bedrock.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/code/generate} — generate code from a natural language description</li>
 *   <li>{@code POST /api/code/explain}  — explain what code does</li>
 *   <li>{@code POST /api/code/review}   — review code for bugs, security, and quality issues</li>
 *   <li>{@code POST /api/code/convert}  — translate code between programming languages</li>
 *   <li>{@code POST /api/code/fix}      — debug and fix broken code</li>
 *   <li>{@code GET  /api/code/languages} — list commonly supported languages</li>
 *   <li>{@code GET  /api/code/health}    — health check</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/code")
@RequiredArgsConstructor
public class CodeGenerationController {

    private final CodeGenerationService codeGenerationService;

    // ── Generate ──────────────────────────────────────────────────────────────

    /**
     * Generates code from a natural language description.
     *
     * <h3>Minimal</h3>
     * <pre>{@code
     * {
     *   "description": "Binary search over a sorted integer array",
     *   "language": "Python"
     * }
     * }</pre>
     *
     * <h3>With framework and requirements</h3>
     * <pre>{@code
     * {
     *   "description": "REST endpoint to create a new user",
     *   "language": "Java",
     *   "framework": "Spring Boot",
     *   "requirements": ["Validate the request body", "Return 201 on success", "Add Javadoc"]
     * }
     * }</pre>
     *
     * @param request description, language, optional framework/requirements
     * @return generated code, explanation, and dependencies
     */
    @PostMapping("/generate")
    public ResponseEntity<CodeGenerateResponse> generate(
            @Valid @RequestBody CodeGenerateRequest request) {
        log.info("POST /api/code/generate — language={}", request.getLanguage());
        return ResponseEntity.ok(codeGenerationService.generate(request));
    }

    // ── Explain ───────────────────────────────────────────────────────────────

    /**
     * Explains what a piece of code does.
     *
     * <h3>Brief</h3>
     * <pre>{@code
     * {
     *   "code": "SELECT * FROM users WHERE id = ?",
     *   "language": "SQL",
     *   "detailLevel": "BRIEF"
     * }
     * }</pre>
     *
     * <h3>Detailed (default)</h3>
     * <pre>{@code
     * {
     *   "code": "def quicksort(arr): ...",
     *   "detailLevel": "DETAILED"
     * }
     * }</pre>
     *
     * @param request code to explain, optional language hint and detail level
     * @return explanation, key points, and complexity rating
     */
    @PostMapping("/explain")
    public ResponseEntity<CodeExplainResponse> explain(
            @Valid @RequestBody CodeExplainRequest request) {
        log.info("POST /api/code/explain — detailLevel={}", request.getDetailLevel());
        return ResponseEntity.ok(codeGenerationService.explain(request));
    }

    // ── Review ────────────────────────────────────────────────────────────────

    /**
     * Reviews code for bugs, security vulnerabilities, performance issues, and style problems.
     * Returns an ordered list of issues (CRITICAL first) and an overall quality rating.
     *
     * <h3>Full review (default)</h3>
     * <pre>{@code { "code": "...", "language": "Java" } }</pre>
     *
     * <h3>Security-only review</h3>
     * <pre>{@code
     * {
     *   "code": "...",
     *   "focusAreas": ["SECURITY"]
     * }
     * }</pre>
     *
     * @param request code and optional language/focus areas
     * @return list of issues, summary, and overall quality rating (1–10)
     */
    @PostMapping("/review")
    public ResponseEntity<CodeReviewResponse> review(
            @Valid @RequestBody CodeReviewRequest request) {
        log.info("POST /api/code/review — language={}, focuses={}",
                request.getLanguage(), request.getFocusAreas());
        return ResponseEntity.ok(codeGenerationService.review(request));
    }

    // ── Convert ───────────────────────────────────────────────────────────────

    /**
     * Translates code from one programming language to another,
     * producing idiomatic output in the target language.
     *
     * <pre>{@code
     * {
     *   "code": "def add(a, b): return a + b",
     *   "sourceLanguage": "Python",
     *   "targetLanguage": "Go"
     * }
     * }</pre>
     *
     * @param request source code and target language
     * @return converted code, source language (detected or provided), and conversion notes
     */
    @PostMapping("/convert")
    public ResponseEntity<CodeConvertResponse> convert(
            @Valid @RequestBody CodeConvertRequest request) {
        log.info("POST /api/code/convert — source={}, target={}",
                request.getSourceLanguage(), request.getTargetLanguage());
        return ResponseEntity.ok(codeGenerationService.convert(request));
    }

    // ── Fix ───────────────────────────────────────────────────────────────────

    /**
     * Debugs and fixes broken or incorrect code.
     * Provide the optional {@code errorMessage} to give the model more context.
     *
     * <h3>With error message</h3>
     * <pre>{@code
     * {
     *   "code": "public int divide(int a, int b) { return a / b; }",
     *   "language": "Java",
     *   "errorMessage": "ArithmeticException: / by zero"
     * }
     * }</pre>
     *
     * <h3>General fix (no specific error)</h3>
     * <pre>{@code
     * {
     *   "code": "def fib(n): return fib(n-1) + fib(n-2)"
     * }
     * }</pre>
     *
     * @param request buggy code, optional language and error description
     * @return corrected code, explanation, and itemised list of changes
     */
    @PostMapping("/fix")
    public ResponseEntity<CodeFixResponse> fix(
            @Valid @RequestBody CodeFixRequest request) {
        log.info("POST /api/code/fix — language={}", request.getLanguage());
        return ResponseEntity.ok(codeGenerationService.fix(request));
    }

    // ── Meta ──────────────────────────────────────────────────────────────────

    /**
     * Returns a list of commonly supported programming languages.
     * The service accepts any language string — this list is informational only.
     */
    @GetMapping("/languages")
    public ResponseEntity<List<String>> listLanguages() {
        return ResponseEntity.ok(List.of(
                "Bash", "C", "C++", "C#", "CSS", "Dart", "Go", "GraphQL", "Groovy",
                "Haskell", "HTML", "Java", "JavaScript", "Kotlin", "Lua", "MATLAB",
                "PHP", "Python", "R", "Ruby", "Rust", "Scala", "Shell", "SQL",
                "Swift", "Terraform", "TypeScript", "YAML", "Zig"));
    }

    /**
     * Returns the available review focus areas with descriptions.
     */
    @GetMapping("/review/focuses")
    public ResponseEntity<List<Map<String, String>>> listReviewFocuses() {
        List<Map<String, String>> focuses = Arrays.stream(ReviewFocus.values())
                .map(f -> Map.of(
                        "focus",       f.name(),
                        "description", f.getDescription()))
                .toList();
        return ResponseEntity.ok(focuses);
    }

    /**
     * Health check.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "code-generation"));
    }
}
