package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response from {@code POST /api/agent/chat}.
 *
 * <p>Includes the model's final answer, a log of every tool the agent invoked,
 * how many Bedrock round-trips were required, and aggregated token usage.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    /** The model's final natural-language answer after all tool calls are resolved. */
    private String reply;

    /** Bedrock model ID that was used. */
    private String modelId;

    /** Ordered log of each tool invocation made during this agent run. */
    private List<ToolCallRecord> toolCalls;

    /** Number of Bedrock Converse round-trips (1 = no tools were called). */
    private int iterations;

    /** Aggregated token usage across all round-trips. */
    private ChatResponse.TokenUsage usage;

    /** Server-side timestamp when the response was produced. */
    private Instant timestamp;
}
