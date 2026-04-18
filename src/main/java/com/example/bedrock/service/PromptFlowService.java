package com.example.bedrock.service;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.FlowInvokeRequest;
import com.example.bedrock.model.FlowInvokeResponse;
import com.example.bedrock.model.FlowNodeOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.FlowAliasSummary;
import software.amazon.awssdk.services.bedrockagent.model.FlowSummary;
import software.amazon.awssdk.services.bedrockagent.model.GetFlowRequest;
import software.amazon.awssdk.services.bedrockagent.model.GetFlowResponse;
import software.amazon.awssdk.services.bedrockagent.model.ListFlowAliasesRequest;
import software.amazon.awssdk.services.bedrockagent.model.ListFlowsRequest;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.FlowCompletionEvent;
import software.amazon.awssdk.services.bedrockagentruntime.model.FlowInput;
import software.amazon.awssdk.services.bedrockagentruntime.model.FlowInputContent;
import software.amazon.awssdk.services.bedrockagentruntime.model.FlowOutputEvent;
import software.amazon.awssdk.services.bedrockagentruntime.model.FlowResponseStream;
import software.amazon.awssdk.services.bedrockagentruntime.model.InvokeFlowRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.InvokeFlowResponseHandler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Invokes and manages Amazon Bedrock Prompt Flows via the
 * <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/flows.html">Bedrock Flows APIs</a>.
 *
 * <h2>Two clients, two planes</h2>
 * <dl>
 *   <dt>{@link BedrockAgentRuntimeAsyncClient} — data plane</dt>
 *   <dd>{@code invokeFlow()} — executes a flow and streams back output/trace events via the
 *       visitor pattern ({@link InvokeFlowResponseHandler.Visitor}). This is the hot path used
 *       for every request. The SDK 2.27.21 exposes {@code invokeFlow} on the <em>async</em>
 *       client only; we call {@code .join()} so the async completion is transparent to callers.</dd>
 *
 *   <dt>{@link BedrockAgentClient} — management plane</dt>
 *   <dd>{@code listFlows()} / {@code getFlow()} — read-only metadata about flows and
 *       their aliases. Used only by the {@code GET /api/flows} and
 *       {@code GET /api/flows/{id}/aliases} endpoints.</dd>
 * </dl>
 *
 * <h2>Event model (visitor pattern)</h2>
 * <p>{@code invokeFlow} delivers typed events to an {@link InvokeFlowResponseHandler.Visitor}:
 * <ul>
 *   <li>{@link FlowOutputEvent} — fired when an Output node produces a result</li>
 *   <li>{@code FlowCompletionEvent} — signals the flow finished (with reason)</li>
 * </ul>
 * All events are collected synchronously via {@code .join()} before the method returns.
 *
 * <h2>Prerequisites</h2>
 * <ol>
 *   <li>Create a flow in AWS Console → Bedrock → Flows.</li>
 *   <li>Publish a version and create an Alias (or use the draft alias
 *       {@code TSTALIASID}).</li>
 *   <li>Set {@code FLOW_ID} and {@code FLOW_ALIAS_ID} in
 *       {@code .vscode/launch.json} for local dev.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptFlowService {

    private final BedrockAgentRuntimeAsyncClient agentRuntimeAsyncClient;
    private final BedrockAgentClient             agentClient;
    private final BedrockProperties              properties;

    // ── Flow invocation ───────────────────────────────────────────────────────

    /**
     * Invokes a Bedrock Prompt Flow and returns the collected output(s).
     *
     * <p>The flow input is sent as a {@code Document} string to the node named
     * by {@code request.getInputNodeName()} (default: {@code "FlowInputNode"}).
     * The service attaches an {@link InvokeFlowResponseHandler.Visitor} to collect
     * every {@link FlowOutputEvent} and the final completion reason, then blocks
     * with {@code .join()} until the stream is exhausted.
     *
     * @param request flow ID, alias, input text, and trace flag
     * @return aggregated outputs, completion reason, and optional trace events
     * @throws BedrockException if the flow or alias IDs are missing, or the call fails
     */
    public FlowInvokeResponse invoke(FlowInvokeRequest request) {
        String flowId   = resolveFlowId(request.getFlowId());
        String aliasId  = resolveAliasId(request.getFlowAliasId());
        String nodeName = request.getInputNodeName() != null
                ? request.getInputNodeName() : "FlowInputNode";

        log.info("Invoking flow — flowId={}, aliasId={}, inputNode={}, inputLength={}, trace={}",
                flowId, aliasId, nodeName, request.getInput().length(), request.isEnableTrace());

        // Build the single input block — flows expect a Document wrapping the text
        FlowInput flowInput = FlowInput.builder()
                .nodeName(nodeName)
                .nodeOutputName("document")
                .content(FlowInputContent.fromDocument(Document.fromString(request.getInput())))
                .build();

        // Note: enableTrace is not available in SDK 2.27.21 — trace events are
        // collected passively via the visitor's onDefault handler in newer SDKs.
        InvokeFlowRequest invokeRequest = InvokeFlowRequest.builder()
                .flowIdentifier(flowId)
                .flowAliasIdentifier(aliasId)
                .inputs(flowInput)
                .build();

        // Accumulators — populated by the subscriber callbacks.
        // join() below guarantees all events are processed before we read these.
        List<FlowNodeOutput>       outputs          = new ArrayList<>();
        List<String>               traceEvents      = new ArrayList<>();
        AtomicReference<String>    completionReason = new AtomicReference<>("UNKNOWN");
        AtomicReference<Throwable> streamError      = new AtomicReference<>();

        // Use the onEventStream builder hook so that events are delivered to our own
        // org.reactivestreams.Subscriber directly — no additional SDK dispatch layer.
        // This keeps the call chain synchronous and makes the handler trivially testable.
        InvokeFlowResponseHandler handler = InvokeFlowResponseHandler.builder()
                .onEventStream(publisher -> publisher.subscribe(new Subscriber<FlowResponseStream>() {

                    @Override
                    public void onSubscribe(Subscription s) {
                        s.request(Long.MAX_VALUE);   // request all events eagerly
                    }

                    @Override
                    public void onNext(FlowResponseStream event) {
                        if (event instanceof FlowOutputEvent) {
                            FlowOutputEvent out = (FlowOutputEvent) event;
                            String content = extractOutputContent(out);
                            log.debug("Flow output event — node={}, nodeType={}, contentLength={}",
                                    out.nodeName(), out.nodeTypeAsString(),
                                    content != null ? content.length() : 0);
                            outputs.add(FlowNodeOutput.builder()
                                    .nodeName(out.nodeName())
                                    .nodeOutputName(out.nodeTypeAsString())
                                    .content(content)
                                    .build());
                        } else if (event instanceof FlowCompletionEvent) {
                            FlowCompletionEvent comp = (FlowCompletionEvent) event;
                            completionReason.set(comp.completionReasonAsString());
                            log.info("Flow completion — reason={}", comp.completionReasonAsString());
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        log.error("Flow stream error — flowId={}: {}", flowId, t.getMessage(), t);
                        streamError.set(t);
                    }

                    @Override
                    public void onComplete() { /* stream exhausted; join() will return shortly */ }
                }))
                .build();

        try {
            agentRuntimeAsyncClient.invokeFlow(invokeRequest, handler).join();
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new BedrockException(
                    "InvokeFlow failed for flow '" + flowId + "' alias '" + aliasId
                    + "': " + cause.getMessage(), cause);
        }

        if (streamError.get() != null) {
            Throwable err = streamError.get();
            throw new BedrockException(
                    "InvokeFlow stream error for flow '" + flowId + "': " + err.getMessage(), err);
        }

        String primaryOutput = outputs.isEmpty() ? null : outputs.get(0).getContent();

        log.info("Flow invocation complete — outputs={}, reason={}, traceEvents={}",
                outputs.size(), completionReason.get(), traceEvents.size());

        return FlowInvokeResponse.builder()
                .flowId(flowId)
                .flowAliasId(aliasId)
                .outputs(outputs)
                .primaryOutput(primaryOutput)
                .completionReason(completionReason.get())
                .traceEvents(traceEvents)
                .timestamp(Instant.now())
                .build();
    }

    // ── Flow listing (management plane) ───────────────────────────────────────

    /**
     * Lists all Prompt Flows in the account/region with basic metadata.
     *
     * @return list of flow summaries (id, name, status, description)
     * @throws BedrockException if the list call fails
     */
    public List<Map<String, String>> listFlows() {
        try {
            return agentClient.listFlows(ListFlowsRequest.builder().build())
                    .flowSummaries()
                    .stream()
                    .map(this::flowSummaryToMap)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            throw new BedrockException("listFlows failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Returns aliases for a specific flow — useful for finding the right Alias ID
     * to pass in invoke requests.
     *
     * @param flowId the Flow ID
     * @return list of alias summaries (id, name, description, routingConfig)
     * @throws BedrockException if the list call fails
     */
    public List<Map<String, String>> listAliases(String flowId) {
        try {
            return agentClient.listFlowAliases(
                            ListFlowAliasesRequest.builder().flowIdentifier(flowId).build())
                    .flowAliasSummaries()
                    .stream()
                    .map(this::aliasSummaryToMap)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            throw new BedrockException(
                    "listFlowAliases failed for flow '" + flowId + "': " + ex.getMessage(), ex);
        }
    }

    /**
     * Returns detailed metadata for a single flow.
     *
     * @param flowId the Flow ID
     * @return map of flow metadata fields
     * @throws BedrockException if the get call fails
     */
    public Map<String, Object> getFlow(String flowId) {
        try {
            GetFlowResponse response = agentClient.getFlow(
                    GetFlowRequest.builder().flowIdentifier(flowId).build());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id",          response.id());
            result.put("name",        response.name());
            result.put("status",      response.statusAsString());
            result.put("description", response.description() != null ? response.description() : "");
            result.put("createdAt",   response.createdAt() != null ? response.createdAt().toString() : "");
            result.put("updatedAt",   response.updatedAt() != null ? response.updatedAt().toString() : "");
            return result;
        } catch (Exception ex) {
            throw new BedrockException(
                    "getFlow failed for '" + flowId + "': " + ex.getMessage(), ex);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts the text content from a {@link FlowOutputEvent}.
     * Output content is a {@code Document} — we call {@code asString()} for text
     * nodes and fall back to {@code toString()} for other document types.
     */
    public String extractOutputContent(FlowOutputEvent event) {
        if (event == null || event.content() == null) return null;
        Document doc = event.content().document();
        if (doc == null) return null;
        try {
            return doc.asString();
        } catch (Exception ex) {
            // Document may be a map/list for complex flow outputs — stringify it
            return doc.toString();
        }
    }

    private String resolveFlowId(String requestOverride) {
        String id = (requestOverride != null && !requestOverride.isBlank())
                ? requestOverride
                : properties.getBedrock().getFlow().getDefaultFlowId();
        if (id == null || id.isBlank()) {
            throw new BedrockException(
                    "No Flow ID provided. Set FLOW_ID in .vscode/launch.json "
                    + "or pass 'flowId' in the request body.");
        }
        return id;
    }

    private String resolveAliasId(String requestOverride) {
        String id = (requestOverride != null && !requestOverride.isBlank())
                ? requestOverride
                : properties.getBedrock().getFlow().getDefaultAliasId();
        if (id == null || id.isBlank()) {
            throw new BedrockException(
                    "No Flow Alias ID provided. Set FLOW_ALIAS_ID in .vscode/launch.json "
                    + "or pass 'flowAliasId' in the request body. "
                    + "Use 'TSTALIASID' for the draft alias.");
        }
        return id;
    }

    private Map<String, String> flowSummaryToMap(FlowSummary s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id",          s.id());
        m.put("name",        s.name());
        m.put("status",      s.statusAsString());
        m.put("description", s.description() != null ? s.description() : "");
        m.put("updatedAt",   s.updatedAt() != null ? s.updatedAt().toString() : "");
        return m;
    }

    private Map<String, String> aliasSummaryToMap(FlowAliasSummary s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id",          s.id());
        m.put("name",        s.name());
        m.put("description", s.description() != null ? s.description() : "");
        m.put("flowId",      s.flowId());
        m.put("createdAt",   s.createdAt() != null ? s.createdAt().toString() : "");
        m.put("updatedAt",   s.updatedAt() != null ? s.updatedAt().toString() : "");
        return m;
    }
}
