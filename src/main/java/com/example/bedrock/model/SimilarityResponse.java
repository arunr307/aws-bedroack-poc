package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response body for {@code POST /api/embeddings/similarity}.
 *
 * <pre>{@code
 * {
 *   "score": 0.94,
 *   "interpretation": "Very similar",
 *   "textA": "AWS Lambda is a serverless compute service.",
 *   "textB": "Lambda lets you run code without managing servers.",
 *   "modelId": "amazon.titan-embed-text-v2:0",
 *   "timestamp": "2025-04-16T22:00:00Z"
 * }
 * }</pre>
 *
 * <h2>Interpreting the score</h2>
 * <table border="1">
 *   <tr><th>Range</th><th>Meaning</th></tr>
 *   <tr><td>0.90 – 1.00</td><td>Very similar / near-duplicate</td></tr>
 *   <tr><td>0.75 – 0.90</td><td>Closely related</td></tr>
 *   <tr><td>0.50 – 0.75</td><td>Somewhat related</td></tr>
 *   <tr><td>0.00 – 0.50</td><td>Weakly or unrelated</td></tr>
 *   <tr><td>&lt; 0.00</td><td>Opposite / contradictory meaning</td></tr>
 * </table>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimilarityResponse {

    /**
     * Cosine similarity score in the range {@code [-1, 1]}.
     * Rounded to 4 decimal places.
     */
    private double score;

    /**
     * Human-readable label for the score:
     * {@code "Very similar"}, {@code "Closely related"},
     * {@code "Somewhat related"}, {@code "Weakly related"}, or {@code "Unrelated / opposite"}.
     */
    private String interpretation;

    /** The first text that was compared. */
    private String textA;

    /** The second text that was compared. */
    private String textB;

    /** The embedding model used for both vectors. */
    private String modelId;

    /** Server-side timestamp. */
    private Instant timestamp;
}
