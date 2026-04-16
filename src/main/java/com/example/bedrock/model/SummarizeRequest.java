package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/summarize}.
 *
 * <h2>Minimal request</h2>
 * <pre>{@code
 * {
 *   "text": "Long article or document text..."
 * }
 * }</pre>
 *
 * <h2>Full request with all options</h2>
 * <pre>{@code
 * {
 *   "text": "Long article or document text...",
 *   "style": "BULLET_POINTS",
 *   "maxWords": 200,
 *   "language": "Spanish",
 *   "focusOn": "security implications",
 *   "modelId": "amazon.nova-pro-v1:0"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummarizeRequest {

    /**
     * The text to summarize. Must not be blank. Supports documents up to ~100 000 characters
     * (approximately 25 000 words). For longer documents, split into sections first.
     */
    @NotBlank(message = "text must not be blank")
    @Size(max = 100_000, message = "text must not exceed 100,000 characters")
    private String text;

    /**
     * Summary style. Defaults to {@link SummaryStyle#BRIEF} if not provided.
     *
     * <ul>
     *   <li>{@code BRIEF}        — 2–3 sentences capturing the core idea</li>
     *   <li>{@code DETAILED}     — Multi-paragraph summary preserving key details</li>
     *   <li>{@code BULLET_POINTS}— Key points as a bulleted list (great for scanning)</li>
     *   <li>{@code HEADLINE}     — Single-line title / headline only</li>
     *   <li>{@code EXECUTIVE}    — Business-style executive summary with key takeaways</li>
     * </ul>
     */
    @Builder.Default
    private SummaryStyle style = SummaryStyle.BRIEF;

    /**
     * Approximate maximum number of words in the summary.
     * When {@code null}, the model decides based on the chosen {@code style}.
     */
    @Positive(message = "maxWords must be a positive number")
    private Integer maxWords;

    /**
     * Language for the output summary.
     * Defaults to {@code "English"}.
     * Example values: {@code "Spanish"}, {@code "French"}, {@code "German"}, {@code "Japanese"}.
     */
    @Builder.Default
    private String language = "English";

    /**
     * Optional focus area — instructs the model to emphasise a particular aspect.
     * Example: {@code "security risks"}, {@code "cost savings"}, {@code "technical details"}.
     */
    private String focusOn;

    /**
     * Optional Bedrock model ID override for this request only.
     * Defaults to the model configured in {@code application.yml}.
     */
    private String modelId;

    // ── Style enum ────────────────────────────────────────────────────────────

    /**
     * Summarization output style.
     */
    public enum SummaryStyle {

        /** 2–3 sentence high-level overview. */
        BRIEF,

        /** Multi-paragraph summary preserving important details. */
        DETAILED,

        /** Key points formatted as a concise bulleted list. */
        BULLET_POINTS,

        /** Single one-line title or headline that captures the essence. */
        HEADLINE,

        /** Business-oriented executive summary with a structured takeaway section. */
        EXECUTIVE
    }
}
