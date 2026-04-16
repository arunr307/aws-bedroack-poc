package com.example.bedrock.service;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.ChatResponse;
import com.example.bedrock.model.SummarizeRequest;
import com.example.bedrock.model.SummarizeRequest.SummaryStyle;
import com.example.bedrock.model.SummarizeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.time.Instant;
import java.util.Arrays;

/**
 * Summarizes text using Amazon Bedrock's Converse API with style-specific system prompts.
 *
 * <h2>How summarization styles work</h2>
 * <p>Each {@link SummaryStyle} maps to a tailored system prompt that instructs the model
 * to produce output in a specific format and length. The user message always contains
 * the raw text to summarize, keeping the prompt and content cleanly separated.
 *
 * <h2>Compression ratio</h2>
 * <p>The response includes an approximate word-count compression ratio so callers can
 * measure how much the model condensed the input.
 *
 * <h2>Large documents</h2>
 * <p>Most Nova/Claude models support context windows of 128 k+ tokens (~96 000 words).
 * For documents exceeding that, split into sections and summarize each independently,
 * then summarize the summaries (map-reduce pattern — a future enhancement).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummarizationService {

    private final BedrockRuntimeClient bedrockClient;
    private final BedrockProperties    properties;

    /**
     * Summarizes the text in {@code request} and returns a {@link SummarizeResponse}.
     *
     * @param request summarization request with text, style, and options
     * @return structured response containing the summary and metadata
     * @throws BedrockException if the Bedrock API call fails
     */
    public SummarizeResponse summarize(SummarizeRequest request) {
        String modelId = resolveModelId(request);

        String systemPrompt = buildSystemPrompt(request);
        String userMessage  = buildUserMessage(request);

        log.debug("Summarizing {} chars with style={}, modelId={}",
                  request.getText().length(), request.getStyle(), modelId);

        ConverseRequest converseRequest = ConverseRequest.builder()
                .modelId(modelId)
                .system(SystemContentBlock.builder().text(systemPrompt).build())
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userMessage))
                        .build())
                // Lower temperature for summarization — we want accurate, not creative output
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(properties.getBedrock().getMaxTokens())
                        .temperature(0.2f)
                        .build())
                .build();

        ConverseResponse converseResponse;
        try {
            converseResponse = bedrockClient.converse(converseRequest);
        } catch (Exception ex) {
            throw new BedrockException("Bedrock summarization failed: " + ex.getMessage(), ex);
        }

        String summary = converseResponse.output()
                .message()
                .content()
                .stream()
                .filter(b -> b.text() != null)
                .map(ContentBlock::text)
                .findFirst()
                .orElseThrow(() -> new BedrockException("Model returned an empty summary"));

        int originalWords = countWords(request.getText());
        int summaryWords  = countWords(summary);
        double ratio      = summaryWords > 0 ? (double) originalWords / summaryWords : 0;

        TokenUsage usage = converseResponse.usage();

        log.debug("Summary complete — originalWords={}, summaryWords={}, ratio={}",
                  originalWords, summaryWords, String.format("%.1f", ratio));

        return SummarizeResponse.builder()
                .summary(summary)
                .style(request.getStyle())
                .originalWordCount(originalWords)
                .summaryWordCount(summaryWords)
                .compressionRatio(Math.round(ratio * 10.0) / 10.0)
                .modelId(modelId)
                .usage(ChatResponse.TokenUsage.builder()
                        .inputTokens(usage != null ? usage.inputTokens()  : 0)
                        .outputTokens(usage != null ? usage.outputTokens() : 0)
                        .totalTokens(usage != null ? usage.totalTokens()   : 0)
                        .build())
                .timestamp(Instant.now())
                .build();
    }

    // ── Prompt construction ───────────────────────────────────────────────────

    /**
     * Builds a style-specific system prompt that shapes the model's output format
     * and length. A low temperature in the inference config keeps the output factual.
     */
    private String buildSystemPrompt(SummarizeRequest request) {
        String language   = request.getLanguage() != null ? request.getLanguage() : "English";
        String focusClause = request.getFocusOn() != null && !request.getFocusOn().isBlank()
                ? " Pay special attention to: " + request.getFocusOn() + "."
                : "";
        String wordLimit  = request.getMaxWords() != null
                ? " Keep your response under " + request.getMaxWords() + " words."
                : "";

        String styleInstructions = switch (request.getStyle()) {
            case BRIEF -> """
                    You are a precise summarization assistant. Summarize the provided text \
                    in 2–3 concise sentences that capture the core idea and most important \
                    points. Do not include unnecessary details or opinions.""";

            case DETAILED -> """
                    You are a thorough summarization assistant. Write a comprehensive summary \
                    of the provided text using multiple paragraphs. Preserve all significant \
                    facts, arguments, and conclusions. Use clear, neutral language.""";

            case BULLET_POINTS -> """
                    You are a summarization assistant specializing in structured output. \
                    Extract the key points from the provided text and present them as a \
                    concise bulleted list. Each bullet should be one clear sentence. \
                    Start each bullet with "•". Do not include an introduction or conclusion.""";

            case HEADLINE -> """
                    You are a headline writer. Read the provided text and produce a single \
                    headline (one sentence, max 15 words) that captures the most important \
                    idea. Output only the headline — no explanation, no punctuation beyond \
                    the headline itself.""";

            case EXECUTIVE -> """
                    You are a business analyst producing executive summaries. Structure your \
                    response with these sections:
                    **Overview** — 2–3 sentences on what this is about.
                    **Key Points** — 3–5 bullet points of the most important findings.
                    **Recommendation / Takeaway** — 1–2 sentences on the main action or conclusion.
                    Use clear, professional business language.""";
        };

        return styleInstructions + focusClause + wordLimit
                + " Respond in " + language + ".";
    }

    /**
     * Builds the user message — always just the raw text, so the model stays focused
     * on the content rather than instructions.
     */
    private String buildUserMessage(SummarizeRequest request) {
        return "Please summarize the following text:\n\n" + request.getText();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveModelId(SummarizeRequest request) {
        if (request.getModelId() != null && !request.getModelId().isBlank()) {
            return request.getModelId();
        }
        return properties.getBedrock().getModelId();
    }

    /**
     * Counts words by splitting on whitespace — fast and sufficient for compression-ratio
     * reporting (no need for a linguistics library).
     */
    private int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) Arrays.stream(text.trim().split("\\s+"))
                .filter(w -> !w.isEmpty())
                .count();
    }
}
