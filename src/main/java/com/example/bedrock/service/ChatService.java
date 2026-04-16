package com.example.bedrock.service;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.ChatMessage;
import com.example.bedrock.model.ChatRequest;
import com.example.bedrock.model.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Core service that sends chat messages to Amazon Bedrock via the
 * <a href="https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html">
 * Converse API</a> and maps the response back to our domain objects.
 *
 * <h2>Why the Converse API?</h2>
 * <ul>
 *   <li>Unified interface across all Bedrock models (Anthropic, Amazon, Cohere, Meta, etc.)</li>
 *   <li>Built-in multi-turn message history support</li>
 *   <li>No need to hand-craft model-specific JSON payloads</li>
 * </ul>
 *
 * <h2>Conversation flow</h2>
 * <ol>
 *   <li>Caller sends a {@link ChatRequest} optionally containing prior
 *       {@code conversationHistory}.</li>
 *   <li>The current user message is appended and the full list is forwarded
 *       to Bedrock.</li>
 *   <li>The model's reply is appended and the updated history is returned
 *       inside {@link ChatResponse} — ready to be stored on the client side
 *       and sent back on the next turn.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final BedrockRuntimeClient bedrockClient;
    private final BedrockProperties    properties;

    /**
     * Sends a chat message to Bedrock and returns the model's reply together
     * with the updated conversation history.
     *
     * @param request incoming chat request (message + optional history)
     * @return structured response containing the reply and token usage
     * @throws BedrockException if the Bedrock API call fails
     */
    public ChatResponse chat(ChatRequest request) {
        String modelId = resolveModelId(request);

        // ── 1. Build the conversation message list ────────────────────────────
        List<ChatMessage> history = buildHistory(request);
        List<Message>     awsMessages = toAwsMessages(history);

        // ── 2. Assemble the Converse request ──────────────────────────────────
        ConverseRequest.Builder converseBuilder = ConverseRequest.builder()
                .modelId(modelId)
                .messages(awsMessages)
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(properties.getBedrock().getMaxTokens())
                        .temperature((float) properties.getBedrock().getTemperature())
                        .build());

        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            converseBuilder.system(SystemContentBlock.builder()
                    .text(request.getSystemPrompt())
                    .build());
        }

        log.debug("Sending Converse request — modelId={}, turns={}", modelId, awsMessages.size());

        // ── 3. Invoke Bedrock ─────────────────────────────────────────────────
        ConverseResponse converseResponse;
        try {
            converseResponse = bedrockClient.converse(converseBuilder.build());
        } catch (Exception ex) {
            throw new BedrockException("Bedrock Converse API call failed: " + ex.getMessage(), ex);
        }

        // ── 4. Extract the assistant's reply ─────────────────────────────────
        String replyText = extractReplyText(converseResponse);
        log.debug("Received reply ({} chars)", replyText.length());

        // ── 5. Append assistant reply to history ──────────────────────────────
        history.add(ChatMessage.builder()
                .role(ChatMessage.Role.ASSISTANT)
                .content(replyText)
                .build());

        // Trim history to the configured max turns (each turn = 1 user + 1 assistant)
        history = trimHistory(history);

        // ── 6. Map token usage ────────────────────────────────────────────────
        TokenUsage usage = converseResponse.usage();
        ChatResponse.TokenUsage tokenUsage = ChatResponse.TokenUsage.builder()
                .inputTokens(usage != null ? usage.inputTokens()  : 0)
                .outputTokens(usage != null ? usage.outputTokens() : 0)
                .totalTokens(usage != null ? usage.totalTokens()   : 0)
                .build();

        return ChatResponse.builder()
                .reply(replyText)
                .modelId(modelId)
                .usage(tokenUsage)
                .conversationHistory(history)
                .timestamp(Instant.now())
                .build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Determines which model to use: per-request override first, then global config.
     */
    private String resolveModelId(ChatRequest request) {
        if (request.getModelId() != null && !request.getModelId().isBlank()) {
            return request.getModelId();
        }
        return properties.getBedrock().getModelId();
    }

    /**
     * Merges the existing conversation history with the new user message.
     */
    private List<ChatMessage> buildHistory(ChatRequest request) {
        List<ChatMessage> history = new ArrayList<>();
        if (request.getConversationHistory() != null) {
            history.addAll(request.getConversationHistory());
        }
        history.add(ChatMessage.builder()
                .role(ChatMessage.Role.USER)
                .content(request.getMessage())
                .build());
        return history;
    }

    /**
     * Converts our domain {@link ChatMessage} list to the AWS SDK {@link Message} list.
     */
    private List<Message> toAwsMessages(List<ChatMessage> messages) {
        return messages.stream()
                .map(m -> Message.builder()
                        .role(m.getRole() == ChatMessage.Role.USER
                                ? ConversationRole.USER
                                : ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText(m.getContent()))
                        .build())
                .toList();
    }

    /**
     * Extracts the plain-text content from the first content block in the
     * model's output.
     */
    private String extractReplyText(ConverseResponse response) {
        return response.output()
                .message()
                .content()
                .stream()
                .filter(b -> b.text() != null)
                .map(ContentBlock::text)
                .findFirst()
                .orElseThrow(() -> new BedrockException("Model returned an empty response"));
    }

    /**
     * Trims the history so it does not exceed
     * {@code maxConversationTurns * 2} messages (1 user + 1 assistant per turn).
     * Oldest messages are dropped first.
     */
    private List<ChatMessage> trimHistory(List<ChatMessage> history) {
        int maxMessages = properties.getBedrock().getMaxConversationTurns() * 2;
        if (maxMessages <= 0 || history.size() <= maxMessages) {
            return history;
        }
        return new ArrayList<>(history.subList(history.size() - maxMessages, history.size()));
    }
}
