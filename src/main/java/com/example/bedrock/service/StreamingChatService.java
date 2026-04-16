package com.example.bedrock.service;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.ChatMessage;
import com.example.bedrock.model.ChatRequest;
import com.example.bedrock.model.ChatResponse;
import com.example.bedrock.model.StreamToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streams a chat response from Amazon Bedrock using the
 * <a href="https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseStream.html">
 * ConverseStream API</a>.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Builds a {@link ConverseStreamRequest} from the incoming {@link ChatRequest}.</li>
 *   <li>Registers a {@link ConverseStreamResponseHandler.Visitor} with callbacks for:
 *     <ul>
 *       <li>{@code onContentBlockDelta} — fired for each text token the model emits</li>
 *       <li>{@code onMetadata} — fired once at the end with token-usage statistics</li>
 *     </ul>
 *   </li>
 *   <li>Each delta callback pushes a {@link StreamToken} JSON object onto the
 *       {@link SseEmitter}, which the HTTP layer flushes to the client immediately.</li>
 *   <li>After the {@link java.util.concurrent.CompletableFuture} returned by
 *       {@code converseStream()} completes, a final {@code done=true} SSE event is sent
 *       and the connection is closed.</li>
 * </ol>
 *
 * <h2>Threading note</h2>
 * <p>The {@link BedrockRuntimeAsyncClient} is non-blocking internally, but we call
 * {@code .join()} to wait for the stream to finish. The controller schedules this on a
 * dedicated {@code streamingExecutor} thread pool — Tomcat request threads are never blocked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamingChatService {

    private final BedrockRuntimeAsyncClient asyncBedrockClient;
    private final BedrockProperties         properties;

    /**
     * Streams a chat response to the supplied {@link SseEmitter}.
     *
     * @param request incoming chat request
     * @param emitter SSE emitter connected to the HTTP response
     */
    public void stream(ChatRequest request, SseEmitter emitter) {
        String modelId = resolveModelId(request);
        List<Message> awsMessages = buildAwsMessages(request);

        ConverseStreamRequest.Builder streamRequestBuilder = ConverseStreamRequest.builder()
                .modelId(modelId)
                .messages(awsMessages)
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(properties.getBedrock().getMaxTokens())
                        .temperature((float) properties.getBedrock().getTemperature())
                        .build());

        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            streamRequestBuilder.system(SystemContentBlock.builder()
                    .text(request.getSystemPrompt())
                    .build());
        }

        // Accumulate token counts from the metadata event emitted at end of stream
        AtomicInteger inputTokens  = new AtomicInteger(0);
        AtomicInteger outputTokens = new AtomicInteger(0);

        ConverseStreamResponseHandler handler = ConverseStreamResponseHandler.builder()
                .subscriber(ConverseStreamResponseHandler.Visitor.builder()

                        // Fired for every text fragment the model produces
                        .onContentBlockDelta(event -> {
                            String text = event.delta().text();
                            if (text != null && !text.isEmpty()) {
                                sendToken(emitter, StreamToken.builder()
                                        .token(text)
                                        .done(false)
                                        .build());
                            }
                        })

                        // Fired once at the end of the stream with token-usage stats
                        .onMetadata(event -> {
                            TokenUsage usage = event.usage();
                            if (usage != null) {
                                inputTokens.set(usage.inputTokens());
                                outputTokens.set(usage.outputTokens());
                            }
                        })

                        .build())
                .build();

        log.debug("Starting ConverseStream — modelId={}", modelId);

        try {
            // converseStream() is non-blocking; .join() waits for the full stream on this thread.
            // The controller already runs this on the streamingExecutor thread pool.
            asyncBedrockClient.converseStream(streamRequestBuilder.build(), handler).join();
        } catch (Exception ex) {
            emitter.completeWithError(new BedrockException(
                    "ConverseStream API call failed: " + ex.getMessage(), ex));
            return;
        }

        // Send the terminal "done" event carrying usage statistics
        int total = inputTokens.get() + outputTokens.get();
        sendToken(emitter, StreamToken.builder()
                .token("")
                .done(true)
                .modelId(modelId)
                .usage(ChatResponse.TokenUsage.builder()
                        .inputTokens(inputTokens.get())
                        .outputTokens(outputTokens.get())
                        .totalTokens(total)
                        .build())
                .build());

        log.debug("ConverseStream complete — inputTokens={}, outputTokens={}",
                inputTokens.get(), outputTokens.get());

        emitter.complete();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String resolveModelId(ChatRequest request) {
        if (request.getModelId() != null && !request.getModelId().isBlank()) {
            return request.getModelId();
        }
        return properties.getBedrock().getModelId();
    }

    private List<Message> buildAwsMessages(ChatRequest request) {
        List<ChatMessage> history = new ArrayList<>();
        if (request.getConversationHistory() != null) {
            history.addAll(request.getConversationHistory());
        }
        history.add(ChatMessage.builder()
                .role(ChatMessage.Role.USER)
                .content(request.getMessage())
                .build());

        return history.stream()
                .map(m -> Message.builder()
                        .role(m.getRole() == ChatMessage.Role.USER
                                ? ConversationRole.USER
                                : ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText(m.getContent()))
                        .build())
                .toList();
    }

    /**
     * Sends a {@link StreamToken} as an SSE {@code data:} event.
     * Swallows {@link IOException} caused by a dropped connection — this is normal
     * (client navigated away, network cut, etc.) and should not produce error logs.
     */
    private void sendToken(SseEmitter emitter, StreamToken token) {
        try {
            emitter.send(SseEmitter.event().data(token));
        } catch (IOException ex) {
            log.debug("SSE write failed — client likely disconnected: {}", ex.getMessage());
            emitter.completeWithError(ex);
        }
    }
}
