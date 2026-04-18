package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single output emitted by a Bedrock Prompt Flow Output node.
 *
 * <p>A flow may have more than one Output node (e.g. one for success, one for
 * a conditional error branch), so the response carries a list of these.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowNodeOutput {

    /** Name of the Output node that produced this result (as configured in the flow). */
    private String nodeName;

    /**
     * Name of the output field on the node (always {@code "document"} for
     * standard Output nodes).
     */
    private String nodeOutputName;

    /** The text content returned by the Output node. */
    private String content;
}
