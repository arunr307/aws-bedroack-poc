package com.example.bedrock.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/flows/invoke}.
 *
 * <h2>Minimal — use IDs configured in application.yml / launch.json</h2>
 * <pre>{@code
 * {
 *   "input": "Summarise the key benefits of AWS Lambda."
 * }
 * }</pre>
 *
 * <h2>Override flow IDs per request</h2>
 * <pre>{@code
 * {
 *   "flowId":      "ABCDEF1234",
 *   "flowAliasId": "TSTALIASID",
 *   "input":       "What is Amazon ECS?"
 * }
 * }</pre>
 *
 * <h2>Custom input node name (advanced)</h2>
 * <p>Only needed when your flow's Input node is not named {@code "FlowInputNode"}.
 * <pre>{@code
 * {
 *   "input":         "Analyse the sentiment of this review.",
 *   "inputNodeName": "DocumentInput"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowInvokeRequest {

    /**
     * Flow ID to invoke.
     * Falls back to {@code aws.bedrock.flow.default-flow-id} when blank.
     */
    private String flowId;

    /**
     * Flow Alias ID to use.
     * Falls back to {@code aws.bedrock.flow.default-alias-id} when blank.
     * Use {@code TSTALIASID} for the draft alias while iterating on a flow.
     */
    private String flowAliasId;

    /**
     * The text passed as the flow's input document.
     * Maps to the {@code document} output of the {@code FlowInputNode}.
     */
    @NotBlank(message = "input must not be blank")
    @Size(max = 100_000, message = "input must not exceed 100,000 characters")
    private String input;

    /**
     * Name of the Input node in the flow (default: {@code "FlowInputNode"}).
     * Override only if your flow uses a custom node name.
     */
    @Builder.Default
    private String inputNodeName = "FlowInputNode";

    /**
     * Whether to include per-node trace events in the response.
     * Useful for debugging — shows which node ran, its input, and its output.
     */
    @Builder.Default
    private boolean enableTrace = false;
}
