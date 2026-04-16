package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single Server-Sent Event (SSE) payload emitted during a streaming chat response.
 *
 * <p>Clients receive a sequence of these events over the SSE connection:
 *
 * <h2>Token event (during generation)</h2>
 * <pre>{@code
 * data: {"token":"Hello","done":false}
 * data: {"token":", how","done":false}
 * data: {"token":" can I help?","done":false}
 * }</pre>
 *
 * <h2>Final event (stream complete)</h2>
 * <pre>{@code
 * data: {"token":"","done":true,"modelId":"amazon.nova-lite-v1:0",
 *         "usage":{"inputTokens":12,"outputTokens":8,"totalTokens":20}}
 * }</pre>
 *
 * <p>A client should accumulate {@code token} values until {@code done == true},
 * then use the {@code usage} block for billing / monitoring.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamToken {

    /** The text fragment produced by the model in this chunk. Empty on the final event. */
    private String token;

    /** {@code true} only on the last event — signals the stream is finished. */
    private boolean done;

    /** Populated on the final event only — the Bedrock model ID that handled the request. */
    private String modelId;

    /** Populated on the final event only — token usage for the entire response. */
    private ChatResponse.TokenUsage usage;
}
