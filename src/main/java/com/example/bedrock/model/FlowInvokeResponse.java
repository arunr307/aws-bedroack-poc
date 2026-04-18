package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/flows/invoke}.
 *
 * <pre>{@code
 * {
 *   "flowId":           "ABCDEF1234",
 *   "flowAliasId":      "TSTALIASID",
 *   "outputs": [
 *     {
 *       "nodeName":       "FlowOutputNode",
 *       "nodeOutputName": "document",
 *       "content":        "AWS Lambda is a serverless compute service..."
 *     }
 *   ],
 *   "primaryOutput":    "AWS Lambda is a serverless compute service...",
 *   "completionReason": "SUCCESS",
 *   "traceEvents":      [],
 *   "timestamp":        "2025-04-18T10:00:00Z"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowInvokeResponse {

    /** The Flow ID that was invoked. */
    private String flowId;

    /** The Alias ID that was used. */
    private String flowAliasId;

    /**
     * All outputs emitted by Output nodes during this invocation.
     * Most flows have exactly one Output node, but branching flows may emit more.
     */
    private List<FlowNodeOutput> outputs;

    /**
     * Convenience field — content of the first output node.
     * {@code null} when no output was produced (e.g. flow errored before the Output node).
     */
    private String primaryOutput;

    /**
     * Completion reason reported by Bedrock.
     * Typically {@code "SUCCESS"} or {@code "FAILURE"}.
     */
    private String completionReason;

    /**
     * Per-node trace events — only populated when {@code enableTrace=true} in the request.
     * Each string is a JSON-encoded trace event from Bedrock.
     */
    private List<String> traceEvents;

    /** Server-side timestamp when the invocation completed. */
    private Instant timestamp;
}
