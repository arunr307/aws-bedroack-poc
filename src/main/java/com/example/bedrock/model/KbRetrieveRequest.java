package com.example.bedrock.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/kb/retrieve}.
 *
 * <p>Calls the Bedrock {@code Retrieve} API — fetches the most relevant chunks
 * from the Knowledge Base <em>without</em> generating an answer.
 * Useful when you want to inspect what the Knowledge Base knows, perform
 * your own re-ranking, or feed the chunks into a custom prompt.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * {
 *   "query": "Lambda cold start mitigation techniques",
 *   "topK": 10
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbRetrieveRequest {

    /** The search query to run against the Knowledge Base. */
    @NotBlank(message = "query must not be blank")
    @Size(max = 10_000, message = "query must not exceed 10,000 characters")
    private String query;

    /**
     * Maximum number of chunks to return (1–100).
     * Defaults to {@code aws.bedrock.knowledge-base.default-top-k} (5).
     */
    @Min(value = 1,   message = "topK must be at least 1")
    @Max(value = 100, message = "topK must not exceed 100")
    @Builder.Default
    private int topK = 0;   // 0 = use config default

    /**
     * Override the Knowledge Base ID for this request.
     * When {@code null}, uses {@code aws.bedrock.knowledge-base.id} from config.
     */
    private String knowledgeBaseId;
}
