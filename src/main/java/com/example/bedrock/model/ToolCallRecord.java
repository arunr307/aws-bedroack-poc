package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A record of a single tool invocation made during an agent run.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRecord {

    /** Name of the tool that was called (e.g. {@code calculator}). */
    private String toolName;

    /** Raw input parameters sent to the tool (as parsed from the model's ToolUse block). */
    private Object input;

    /** String result returned by the tool execution. */
    private String output;
}
