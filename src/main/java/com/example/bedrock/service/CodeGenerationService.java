package com.example.bedrock.service;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides five code-intelligence operations powered by Amazon Bedrock:
 *
 * <ol>
 *   <li><strong>Generate</strong> — produce code from a natural language description</li>
 *   <li><strong>Explain</strong>  — explain what a piece of code does</li>
 *   <li><strong>Review</strong>   — find bugs, security flaws, and quality issues</li>
 *   <li><strong>Convert</strong>  — translate code between programming languages</li>
 *   <li><strong>Fix</strong>      — debug and correct broken code</li>
 * </ol>
 *
 * <h2>Prompt strategy</h2>
 * <p>Every operation uses the Bedrock Converse API with:
 * <ul>
 *   <li>A fixed system prompt instructing the model to return <em>only</em> valid JSON</li>
 *   <li>A per-operation user message with an exact JSON schema the model must follow</li>
 *   <li>{@code temperature = 0.2} for deterministic, structured output</li>
 * </ul>
 *
 * <h2>JSON parsing</h2>
 * <p>The model sometimes wraps JSON in markdown fences despite instructions.
 * A two-stage extraction (fence-strip → brace-search) handles this robustly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeGenerationService {

    /** Strips ` ```json ... ``` ` or ` ``` ... ``` ` fences. */
    private static final Pattern FENCE_PATTERN =
            Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");

    private static final String JSON_SYSTEM_PROMPT = """
            You are an expert software engineer and code assistant.
            You respond ONLY with a valid JSON object — no markdown fences, no prose, no explanation outside the JSON.
            Every key in the JSON must match exactly the schema described in the user message.
            String values that contain code must preserve indentation and newlines using \\n.
            If a list is empty, return an empty array []. Never omit required keys.
            """;

    private final BedrockRuntimeClient bedrockClient;
    private final BedrockProperties    properties;
    private final ObjectMapper         objectMapper;

    // ── Generate ──────────────────────────────────────────────────────────────

    /**
     * Generates code from a natural language description.
     *
     * @param request description, language, optional framework and requirements
     * @return generated code, explanation, and required dependencies
     */
    public CodeGenerateResponse generate(CodeGenerateRequest request) {
        String modelId = resolveModelId(request.getModelId());
        log.info("Code generate — language={}, model={}", request.getLanguage(), modelId);

        StringBuilder sb = new StringBuilder();
        sb.append("Generate ").append(request.getLanguage()).append(" code for the following:\n\n");
        sb.append("DESCRIPTION: ").append(request.getDescription()).append("\n");

        if (request.getFramework() != null && !request.getFramework().isBlank()) {
            sb.append("FRAMEWORK: ").append(request.getFramework()).append("\n");
        }
        if (request.getRequirements() != null && !request.getRequirements().isEmpty()) {
            sb.append("REQUIREMENTS:\n");
            request.getRequirements().forEach(r -> sb.append("- ").append(r).append("\n"));
        }

        sb.append("""

                Return a JSON object with exactly these keys:
                {
                  "code":         the complete, runnable code as a string (use \\n for newlines),
                  "language":     the programming language name (e.g. "Java"),
                  "explanation":  brief explanation of the implementation and key design decisions,
                  "dependencies": array of external libraries/imports needed (empty array if none)
                }
                """);

        JsonNode root = callAndParse(sb.toString(), modelId);

        ChatResponse.TokenUsage usage = extractUsageFromContext();

        return CodeGenerateResponse.builder()
                .code(textOf(root, "code"))
                .language(textOf(root, "language"))
                .explanation(textOf(root, "explanation"))
                .dependencies(stringListOf(root, "dependencies"))
                .modelId(modelId)
                .usage(lastUsage)
                .timestamp(Instant.now())
                .build();
    }

    // ── Explain ───────────────────────────────────────────────────────────────

    /**
     * Explains what a piece of code does.
     *
     * @param request code snippet, optional language hint, and detail level
     * @return plain-language explanation, key points, and complexity rating
     */
    public CodeExplainResponse explain(CodeExplainRequest request) {
        String modelId = resolveModelId(request.getModelId());
        log.info("Code explain — language={}, detailLevel={}, model={}",
                request.getLanguage(), request.getDetailLevel(), modelId);

        String detailInstruction = request.getDetailLevel() == DetailLevel.BRIEF
                ? "Write a concise 2-4 sentence explanation covering only what the code does."
                : "Write a detailed explanation covering: what the code does, how it works step-by-step, " +
                  "edge cases, time/space complexity (if applicable), and any notable patterns or pitfalls.";

        String langHint = request.getLanguage() != null && !request.getLanguage().isBlank()
                ? "Language: " + request.getLanguage() + "\n"
                : "Auto-detect the programming language.\n";

        String userMessage = langHint +
                detailInstruction + "\n\n" +
                "CODE:\n" + request.getCode() + "\n\n" +
                """
                Return a JSON object with exactly these keys:
                {
                  "language":    the detected or provided programming language name,
                  "explanation": the explanation text,
                  "keyPoints":   array of bullet-point highlights (up to 8 items; empty array for BRIEF),
                  "complexity":  one of SIMPLE | MODERATE | COMPLEX
                }
                """;

        JsonNode root = callAndParse(userMessage, modelId);

        return CodeExplainResponse.builder()
                .language(textOf(root, "language"))
                .explanation(textOf(root, "explanation"))
                .keyPoints(stringListOf(root, "keyPoints"))
                .complexity(textOf(root, "complexity"))
                .modelId(modelId)
                .usage(lastUsage)
                .timestamp(Instant.now())
                .build();
    }

    // ── Review ────────────────────────────────────────────────────────────────

    /**
     * Reviews code for bugs, security flaws, performance issues, and style problems.
     *
     * @param request code snippet, optional language, and optional focus areas
     * @return list of issues (ordered by severity), summary, and overall rating
     */
    public CodeReviewResponse review(CodeReviewRequest request) {
        String modelId = resolveModelId(request.getModelId());
        List<ReviewFocus> focuses = request.getFocusAreas() == null || request.getFocusAreas().isEmpty()
                ? List.copyOf(EnumSet.allOf(ReviewFocus.class))
                : request.getFocusAreas();

        log.info("Code review — language={}, focuses={}, model={}",
                request.getLanguage(), focuses, modelId);

        StringBuilder sb = new StringBuilder();
        if (request.getLanguage() != null && !request.getLanguage().isBlank()) {
            sb.append("Language: ").append(request.getLanguage()).append("\n");
        }
        sb.append("Review the code below focusing on these areas:\n");
        focuses.forEach(f -> sb.append("- ").append(f.name())
                .append(": ").append(f.getDescription()).append("\n"));

        sb.append("\nCODE:\n").append(request.getCode()).append("\n\n");
        sb.append("""
                Return a JSON object with exactly these keys:
                {
                  "issues": [
                    {
                      "severity":      one of CRITICAL | HIGH | MEDIUM | LOW | INFO,
                      "category":      one of BUG | SECURITY | PERFORMANCE | STYLE | MAINTAINABILITY,
                      "description":   clear description of the issue and why it matters,
                      "lineReference": approximate location e.g. "line 12" or null if global,
                      "suggestion":    concrete suggestion to fix or improve it
                    }
                  ],
                  "summary":       overall summary of code quality and most important findings,
                  "overallRating": integer 1-10 (1=very poor, 10=excellent)
                }
                Issues must be ordered by severity (CRITICAL first). Return [] if no issues found.
                """);

        JsonNode root = callAndParse(sb.toString(), modelId);

        List<CodeReviewResponse.ReviewIssue> issues = parseIssues(root);

        return CodeReviewResponse.builder()
                .issues(issues)
                .summary(textOf(root, "summary"))
                .overallRating(intOf(root, "overallRating"))
                .modelId(modelId)
                .usage(lastUsage)
                .timestamp(Instant.now())
                .build();
    }

    // ── Convert ───────────────────────────────────────────────────────────────

    /**
     * Converts code from one programming language to another.
     *
     * @param request code, optional source language, and required target language
     * @return converted code, detected source language, and conversion notes
     */
    public CodeConvertResponse convert(CodeConvertRequest request) {
        String modelId = resolveModelId(request.getModelId());
        log.info("Code convert — source={}, target={}, model={}",
                request.getSourceLanguage(), request.getTargetLanguage(), modelId);

        String srcHint = request.getSourceLanguage() != null && !request.getSourceLanguage().isBlank()
                ? "Source language: " + request.getSourceLanguage() + "\n"
                : "Auto-detect the source language.\n";

        String userMessage = srcHint +
                "Target language: " + request.getTargetLanguage() + "\n\n" +
                "Convert the following code to " + request.getTargetLanguage() + ".\n" +
                "Produce idiomatic " + request.getTargetLanguage() + " — do not do a literal translation.\n\n" +
                "CODE:\n" + request.getCode() + "\n\n" +
                """
                Return a JSON object with exactly these keys:
                {
                  "convertedCode":  the complete converted code as a string (use \\n for newlines),
                  "sourceLanguage": the detected or provided source language name,
                  "targetLanguage": the target language name,
                  "notes":          array of strings noting idiom differences, removed constructs,
                                    required package changes, or behavioural caveats (empty array if none)
                }
                """;

        JsonNode root = callAndParse(userMessage, modelId);

        return CodeConvertResponse.builder()
                .convertedCode(textOf(root, "convertedCode"))
                .sourceLanguage(textOf(root, "sourceLanguage"))
                .targetLanguage(textOf(root, "targetLanguage"))
                .notes(stringListOf(root, "notes"))
                .modelId(modelId)
                .usage(lastUsage)
                .timestamp(Instant.now())
                .build();
    }

    // ── Fix ───────────────────────────────────────────────────────────────────

    /**
     * Debugs and fixes broken or incorrect code.
     *
     * @param request buggy code, optional language hint, optional error message
     * @return corrected code, explanation, and itemised list of changes
     */
    public CodeFixResponse fix(CodeFixRequest request) {
        String modelId = resolveModelId(request.getModelId());
        log.info("Code fix — language={}, hasError={}, model={}",
                request.getLanguage(), request.getErrorMessage() != null, modelId);

        StringBuilder sb = new StringBuilder();
        if (request.getLanguage() != null && !request.getLanguage().isBlank()) {
            sb.append("Language: ").append(request.getLanguage()).append("\n");
        }
        if (request.getErrorMessage() != null && !request.getErrorMessage().isBlank()) {
            sb.append("Error / wrong behaviour: ").append(request.getErrorMessage()).append("\n");
        } else {
            sb.append("No specific error provided — perform a general correctness review and fix all issues found.\n");
        }
        sb.append("\nCODE TO FIX:\n").append(request.getCode()).append("\n\n");
        sb.append("""
                Return a JSON object with exactly these keys:
                {
                  "fixedCode":   the corrected, complete code as a string (use \\n for newlines),
                  "language":    the detected or provided programming language name,
                  "explanation": human-readable explanation of what was wrong and how it was fixed,
                  "changes":     array of strings — one entry per change made (e.g. "Added null check at line 5")
                }
                If the code is already correct, return it unchanged and explain that no fixes were needed.
                """);

        JsonNode root = callAndParse(sb.toString(), modelId);

        return CodeFixResponse.builder()
                .fixedCode(textOf(root, "fixedCode"))
                .language(textOf(root, "language"))
                .explanation(textOf(root, "explanation"))
                .changes(stringListOf(root, "changes"))
                .modelId(modelId)
                .usage(lastUsage)
                .timestamp(Instant.now())
                .build();
    }

    // ── Shared Bedrock invocation ─────────────────────────────────────────────

    /**
     * Holds the token usage from the most recent Bedrock call so individual
     * operation methods can attach it to their response without passing it through
     * the JSON parsing chain.
     */
    private ChatResponse.TokenUsage lastUsage;

    /**
     * Calls the Bedrock Converse API with the JSON system prompt and a per-operation
     * user message, then strips markdown fences and parses the response as JSON.
     */
    public JsonNode callAndParse(String userMessage, String modelId) {
        ConverseRequest request = ConverseRequest.builder()
                .modelId(modelId)
                .system(SystemContentBlock.builder().text(JSON_SYSTEM_PROMPT).build())
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userMessage))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(4096)
                        .temperature(0.2f)
                        .build())
                .build();

        ConverseResponse response;
        try {
            response = bedrockClient.converse(request);
        } catch (Exception ex) {
            throw new BedrockException("Bedrock code operation failed: " + ex.getMessage(), ex);
        }

        // Capture usage for the calling method
        TokenUsage sdkUsage = response.usage();
        lastUsage = ChatResponse.TokenUsage.builder()
                .inputTokens(sdkUsage  != null ? sdkUsage.inputTokens()   : 0)
                .outputTokens(sdkUsage != null ? sdkUsage.outputTokens()  : 0)
                .totalTokens(sdkUsage  != null ? sdkUsage.totalTokens()   : 0)
                .build();

        String rawText = response.output()
                .message()
                .content()
                .stream()
                .filter(b -> b.text() != null)
                .map(ContentBlock::text)
                .findFirst()
                .orElseThrow(() -> new BedrockException("Model returned an empty response"));

        return parseJson(rawText);
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    /**
     * Strips markdown fences (if present) then parses the JSON.
     * Falls back to extracting the outermost {@code { ... }} block.
     */
    public JsonNode parseJson(String rawText) {
        String cleaned = stripFences(rawText);
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception firstEx) {
            int start = cleaned.indexOf('{');
            int end   = cleaned.lastIndexOf('}');
            if (start != -1 && end > start) {
                try {
                    return objectMapper.readTree(cleaned.substring(start, end + 1));
                } catch (Exception secondEx) {
                    log.warn("JSON parse failed after brace extraction. Raw: {}", rawText);
                    throw new BedrockException(
                            "Model returned unparseable JSON: " + secondEx.getMessage(), secondEx);
                }
            }
            log.warn("No JSON object found in model output. Raw: {}", rawText);
            throw new BedrockException(
                    "Model returned unparseable JSON: " + firstEx.getMessage(), firstEx);
        }
    }

    private String stripFences(String text) {
        Matcher m = FENCE_PATTERN.matcher(text.trim());
        return m.find() ? m.group(1).trim() : text.trim();
    }

    private String textOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return (node != null && !node.isNull()) ? node.asText() : null;
    }

    private int intOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return (node != null && node.isNumber()) ? node.asInt() : 0;
    }

    private List<String> stringListOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(el -> result.add(el.asText()));
        return result;
    }

    private List<CodeReviewResponse.ReviewIssue> parseIssues(JsonNode root) {
        JsonNode issuesNode = root.get("issues");
        if (issuesNode == null || !issuesNode.isArray()) return List.of();
        List<CodeReviewResponse.ReviewIssue> issues = new ArrayList<>();
        for (JsonNode issue : issuesNode) {
            issues.add(CodeReviewResponse.ReviewIssue.builder()
                    .severity(textOf(issue, "severity"))
                    .category(textOf(issue, "category"))
                    .description(textOf(issue, "description"))
                    .lineReference(textOf(issue, "lineReference"))
                    .suggestion(textOf(issue, "suggestion"))
                    .build());
        }
        return issues;
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private String resolveModelId(String requestOverride) {
        if (requestOverride != null && !requestOverride.isBlank()) return requestOverride;
        return properties.getBedrock().getModelId();
    }

    /** Only used to satisfy the return type — real usage comes from {@link #lastUsage}. */
    private ChatResponse.TokenUsage extractUsageFromContext() {
        return lastUsage;
    }
}
