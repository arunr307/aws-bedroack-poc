package com.example.bedrock;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.FlowInvokeRequest;
import com.example.bedrock.model.FlowInvokeResponse;
import com.example.bedrock.service.PromptFlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.FlowAliasSummary;
import software.amazon.awssdk.services.bedrockagent.model.FlowStatus;
import software.amazon.awssdk.services.bedrockagent.model.FlowSummary;
import software.amazon.awssdk.services.bedrockagent.model.GetFlowRequest;
import software.amazon.awssdk.services.bedrockagent.model.GetFlowResponse;
import software.amazon.awssdk.services.bedrockagent.model.ListFlowAliasesRequest;
import software.amazon.awssdk.services.bedrockagent.model.ListFlowAliasesResponse;
import software.amazon.awssdk.services.bedrockagent.model.ListFlowsRequest;
import software.amazon.awssdk.services.bedrockagent.model.ListFlowsResponse;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.FlowCompletionEvent;
import software.amazon.awssdk.services.bedrockagentruntime.model.FlowCompletionReason;
import software.amazon.awssdk.services.bedrockagentruntime.model.FlowOutputContent;
import software.amazon.awssdk.services.bedrockagentruntime.model.FlowOutputEvent;
import software.amazon.awssdk.services.bedrockagentruntime.model.FlowResponseStream;
import software.amazon.awssdk.services.bedrockagentruntime.model.InvokeFlowRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.InvokeFlowResponse;
import software.amazon.awssdk.services.bedrockagentruntime.model.InvokeFlowResponseHandler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PromptFlowService}.
 *
 * <p>Both the async runtime client ({@link BedrockAgentRuntimeAsyncClient}) and the management
 * client ({@link BedrockAgentClient}) are mocked — no real AWS calls are made.
 *
 * <p>The async client's {@code invokeFlow(request, handler)} is stubbed via
 * {@code doAnswer}: the answer drives test events through the real
 * {@link InvokeFlowResponseHandler} (built inside the service) by calling
 * {@code handler.onEventStream(publisher)} with a synthetic publisher that emits
 * {@link FlowOutputEvent} / {@link FlowCompletionEvent} objects — both of which
 * implement {@link FlowResponseStream} and are dispatched to the visitor callbacks.
 */
@ExtendWith(MockitoExtension.class)
class PromptFlowServiceTest {

    @Mock private BedrockAgentRuntimeAsyncClient agentRuntimeAsyncClient;
    @Mock private BedrockAgentClient             agentClient;

    private PromptFlowService service;

    private static final String FLOW_ID  = "TEST-FLOW-0001";
    private static final String ALIAS_ID = "TSTALIASID";

    @BeforeEach
    void setUp() {
        BedrockProperties props = new BedrockProperties();
        props.setRegion("us-east-1");

        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        BedrockProperties.Flow flow = new BedrockProperties.Flow();
        flow.setDefaultFlowId(FLOW_ID);
        flow.setDefaultAliasId(ALIAS_ID);
        bedrock.setFlow(flow);
        props.setBedrock(bedrock);

        service = new PromptFlowService(agentRuntimeAsyncClient, agentClient, props);
    }

    // ── invoke ────────────────────────────────────────────────────────────────

    @Test
    void invoke_singleOutput_returnsOutputAndCompletion() {
        stubInvokeFlow("Lambda is a serverless compute service.", "SUCCESS");

        FlowInvokeRequest request = FlowInvokeRequest.builder()
                .input("What is AWS Lambda?")
                .build();

        FlowInvokeResponse response = service.invoke(request);

        assertThat(response.getFlowId()).isEqualTo(FLOW_ID);
        assertThat(response.getFlowAliasId()).isEqualTo(ALIAS_ID);
        assertThat(response.getOutputs()).hasSize(1);
        assertThat(response.getOutputs().get(0).getContent())
                .isEqualTo("Lambda is a serverless compute service.");
        assertThat(response.getOutputs().get(0).getNodeName()).isEqualTo("FlowOutputNode");
        assertThat(response.getPrimaryOutput())
                .isEqualTo("Lambda is a serverless compute service.");
        assertThat(response.getCompletionReason()).isEqualTo("SUCCESS");
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void invoke_withFlowIdOverride_usesOverride() {
        stubInvokeFlow("Answer from custom flow.", "SUCCESS");

        FlowInvokeRequest request = FlowInvokeRequest.builder()
                .flowId("CUSTOM-FLOW-9999")
                .flowAliasId("CUSTOM-ALIAS")
                .input("Some question")
                .build();

        FlowInvokeResponse response = service.invoke(request);

        assertThat(response.getFlowId()).isEqualTo("CUSTOM-FLOW-9999");
        assertThat(response.getFlowAliasId()).isEqualTo("CUSTOM-ALIAS");
    }

    @Test
    void invoke_noOutputEvents_primaryOutputIsNull() {
        // Flow that completes without firing any output nodes
        FlowCompletionEvent completionEvent = FlowCompletionEvent.builder()
                .completionReason(FlowCompletionReason.SUCCESS)
                .build();

        stubInvokeFlowWithEvents(completionEvent);  // only completion, no output

        FlowInvokeResponse response = service.invoke(
                FlowInvokeRequest.builder().input("trigger").build());

        assertThat(response.getOutputs()).isEmpty();
        assertThat(response.getPrimaryOutput()).isNull();
        assertThat(response.getCompletionReason()).isEqualTo("SUCCESS");
    }

    @Test
    void invoke_traceDisabled_traceEventsEmpty() {
        stubInvokeFlow("Answer.", "SUCCESS");

        FlowInvokeRequest request = FlowInvokeRequest.builder()
                .input("Question")
                .enableTrace(false)
                .build();

        FlowInvokeResponse response = service.invoke(request);

        assertThat(response.getTraceEvents()).isEmpty();
    }

    @Test
    void invoke_runtimeClientThrows_wrapsInBedrockException() {
        when(agentRuntimeAsyncClient.invokeFlow(any(InvokeFlowRequest.class), any(InvokeFlowResponseHandler.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> service.invoke(
                FlowInvokeRequest.builder().input("Question").build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("InvokeFlow failed");
    }

    // ── missing IDs ───────────────────────────────────────────────────────────

    @Test
    void invoke_noFlowIdConfigured_throwsBedrockException() {
        PromptFlowService unconfigured = serviceWithoutIds();

        assertThatThrownBy(() -> unconfigured.invoke(
                FlowInvokeRequest.builder().input("Question").build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("No Flow ID");
    }

    @Test
    void invoke_noAliasIdConfigured_throwsBedrockException() {
        PromptFlowService noAlias = serviceWithFlowIdOnly();

        assertThatThrownBy(() -> noAlias.invoke(
                FlowInvokeRequest.builder().input("Question").build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("No Flow Alias ID");
    }

    @Test
    void invoke_requestOverridesConfig_usesRequestValues() {
        PromptFlowService unconfigured = serviceWithoutIds();
        stubInvokeFlow("Override answer.", "SUCCESS");

        // IDs supplied in the request itself — no config needed
        FlowInvokeResponse response = unconfigured.invoke(
                FlowInvokeRequest.builder()
                        .flowId("REQ-FLOW-ID")
                        .flowAliasId("REQ-ALIAS-ID")
                        .input("Question")
                        .build());

        assertThat(response.getFlowId()).isEqualTo("REQ-FLOW-ID");
    }

    // ── listFlows ─────────────────────────────────────────────────────────────

    @Test
    void listFlows_returnsFlowSummaries() {
        FlowSummary summary = FlowSummary.builder()
                .id("FLOW-A")
                .name("My Summarizer Flow")
                .status(FlowStatus.PREPARED)
                .description("Summarizes documents")
                .updatedAt(Instant.parse("2025-04-17T10:00:00Z"))
                .build();

        when(agentClient.listFlows(any(ListFlowsRequest.class)))
                .thenReturn(ListFlowsResponse.builder()
                        .flowSummaries(summary)
                        .build());

        List<Map<String, String>> flows = service.listFlows();

        assertThat(flows).hasSize(1);
        assertThat(flows.get(0)).containsEntry("id", "FLOW-A");
        assertThat(flows.get(0)).containsEntry("name", "My Summarizer Flow");
        assertThat(flows.get(0)).containsEntry("status", "Prepared");
        assertThat(flows.get(0)).containsEntry("description", "Summarizes documents");
    }

    @Test
    void listFlows_agentClientThrows_wrapsInBedrockException() {
        when(agentClient.listFlows(any(ListFlowsRequest.class)))
                .thenThrow(new RuntimeException("Unauthorized"));

        assertThatThrownBy(() -> service.listFlows())
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("listFlows failed");
    }

    // ── listAliases ───────────────────────────────────────────────────────────

    @Test
    void listAliases_returnsAliasSummaries() {
        FlowAliasSummary alias = FlowAliasSummary.builder()
                .id("TSTALIASID")
                .name("Draft")
                .flowId(FLOW_ID)
                .description("Draft alias")
                .createdAt(Instant.parse("2025-04-17T10:00:00Z"))
                .updatedAt(Instant.parse("2025-04-17T10:00:00Z"))
                .build();

        when(agentClient.listFlowAliases(any(ListFlowAliasesRequest.class)))
                .thenReturn(ListFlowAliasesResponse.builder()
                        .flowAliasSummaries(alias)
                        .build());

        List<Map<String, String>> aliases = service.listAliases(FLOW_ID);

        assertThat(aliases).hasSize(1);
        assertThat(aliases.get(0)).containsEntry("id", "TSTALIASID");
        assertThat(aliases.get(0)).containsEntry("name", "Draft");
        assertThat(aliases.get(0)).containsEntry("flowId", FLOW_ID);
    }

    // ── getFlow ───────────────────────────────────────────────────────────────

    @Test
    void getFlow_returnsFlowMetadata() {
        when(agentClient.getFlow(any(GetFlowRequest.class)))
                .thenReturn(GetFlowResponse.builder()
                        .id(FLOW_ID)
                        .name("My Flow")
                        .status(FlowStatus.PREPARED)
                        .description("A test flow")
                        .createdAt(Instant.parse("2025-04-17T10:00:00Z"))
                        .updatedAt(Instant.parse("2025-04-17T11:00:00Z"))
                        .build());

        Map<String, Object> flow = service.getFlow(FLOW_ID);

        assertThat(flow).containsEntry("id", FLOW_ID);
        assertThat(flow).containsEntry("name", "My Flow");
        assertThat(flow).containsEntry("status", "Prepared");
        assertThat(flow).containsEntry("description", "A test flow");
    }

    // ── extractOutputContent ──────────────────────────────────────────────────

    @Test
    void extractOutputContent_stringDocument_returnsText() {
        FlowOutputEvent event = FlowOutputEvent.builder()
                .nodeName("FlowOutputNode")
                .content(FlowOutputContent.fromDocument(Document.fromString("Hello!")))
                .build();

        assertThat(service.extractOutputContent(event)).isEqualTo("Hello!");
    }

    @Test
    void extractOutputContent_nullEvent_returnsNull() {
        assertThat(service.extractOutputContent(null)).isNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Stubs a full output+completion flow invocation with a single output node result.
     */
    private void stubInvokeFlow(String outputText, String completionReason) {
        FlowOutputEvent outputEvent = FlowOutputEvent.builder()
                .nodeName("FlowOutputNode")
                .content(FlowOutputContent.fromDocument(Document.fromString(outputText)))
                .build();

        FlowCompletionEvent completionEvent = FlowCompletionEvent.builder()
                .completionReason(FlowCompletionReason.fromValue(completionReason))
                .build();

        stubInvokeFlowWithEvents(outputEvent, completionEvent);
    }

    /**
     * Stubs {@code invokeFlow} to drive the given events through the real
     * {@link InvokeFlowResponseHandler} that the service builds internally.
     *
     * <p>Both {@link FlowOutputEvent} and {@link FlowCompletionEvent} implement
     * {@link FlowResponseStream}, so they can be emitted directly by the synthetic
     * publisher. The handler routes each event to the correct visitor callback via
     * {@link FlowResponseStream#accept(InvokeFlowResponseHandler.Visitor)}.
     */
    private void stubInvokeFlowWithEvents(FlowResponseStream... events) {
        doAnswer(invocation -> {
            InvokeFlowResponseHandler handler = invocation.getArgument(1);

            // Simulate the SDK's internal event dispatch:
            // 1. Deliver the initial response metadata
            handler.responseReceived(InvokeFlowResponse.builder().build());

            // 2. Publish the test events through the handler's internal subscriber
            //    (which calls event.accept(visitor) → visitFlowOutputEvent / visitFlowCompletionEvent)
            handler.onEventStream(subscriber ->
                    subscriber.onSubscribe(new Subscription() {
                        @Override
                        public void request(long n) {
                            for (FlowResponseStream event : events) {
                                subscriber.onNext(event);
                            }
                            subscriber.onComplete();
                        }
                        @Override
                        public void cancel() { /* no-op */ }
                    }));

            // 3. Signal stream completion
            handler.complete();

            return CompletableFuture.completedFuture(null);
        }).when(agentRuntimeAsyncClient)
          .invokeFlow(any(InvokeFlowRequest.class), any(InvokeFlowResponseHandler.class));
    }

    private PromptFlowService serviceWithoutIds() {
        BedrockProperties props = new BedrockProperties();
        props.setRegion("us-east-1");
        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        bedrock.setFlow(new BedrockProperties.Flow()); // blank IDs
        props.setBedrock(bedrock);
        return new PromptFlowService(agentRuntimeAsyncClient, agentClient, props);
    }

    private PromptFlowService serviceWithFlowIdOnly() {
        BedrockProperties props = new BedrockProperties();
        props.setRegion("us-east-1");
        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        BedrockProperties.Flow flow = new BedrockProperties.Flow();
        flow.setDefaultFlowId(FLOW_ID);
        // alias intentionally left blank
        bedrock.setFlow(flow);
        props.setBedrock(bedrock);
        return new PromptFlowService(agentRuntimeAsyncClient, agentClient, props);
    }
}
