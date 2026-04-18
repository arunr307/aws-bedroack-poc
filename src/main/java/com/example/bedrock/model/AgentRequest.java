package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for {@code POST /api/agent/chat}.
 *
 * <h2>Basic usage</h2>
 * <pre>{@code
 * {
 *   "message": "What is 1234 * 5678?"
 * }
 * }</pre>
 *
 * <h2>Restrict which tools are available</h2>
 * <pre>{@code
 * {
 *   "message": "Convert 100 Celsius to Fahrenheit",
 *   "enabledTools": ["unit_converter"]
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {

    @NotBlank(message = "message must not be blank")
    @Size(max = 100_000, message = "message must not exceed 100,000 characters")
    private String message;

    /** Optional Bedrock model ID override (falls back to {@code aws.bedrock.model-id}). */
    private String modelId;

    /** Optional system prompt overriding the default agent persona. */
    private String systemPrompt;

    /** Optional prior conversation turns for multi-turn agent sessions. */
    private List<ChatMessage> conversationHistory;

    /**
     * Subset of tool names to enable for this request.
     * When {@code null} or empty, all built-in tools are enabled.
     * Valid values: {@code calculator}, {@code get_current_time},
     * {@code string_utils}, {@code unit_converter}.
     */
    private List<String> enabledTools;
}
