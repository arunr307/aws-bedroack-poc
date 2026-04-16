package com.example.bedrock;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.ChatMessage;
import com.example.bedrock.model.ChatRequest;
import com.example.bedrock.model.ChatResponse;
import com.example.bedrock.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChatService}.
 *
 * <p>The Bedrock client is mocked so that no real AWS calls are made.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private BedrockRuntimeClient bedrockClient;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        BedrockProperties props = new BedrockProperties();
        props.setRegion("us-east-1");

        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        bedrock.setModelId("anthropic.claude-3-5-sonnet-20241022-v2:0");
        bedrock.setMaxTokens(2048);
        bedrock.setTemperature(0.7);
        bedrock.setMaxConversationTurns(10);
        props.setBedrock(bedrock);

        chatService = new ChatService(bedrockClient, props);
    }

    @Test
    void chat_stateless_returnsReply() {
        // Given
        stubBedrockResponse("Paris is the capital of France.");

        ChatRequest request = ChatRequest.builder()
                .message("What is the capital of France?")
                .build();

        // When
        ChatResponse response = chatService.chat(request);

        // Then
        assertThat(response.getReply()).isEqualTo("Paris is the capital of France.");
        assertThat(response.getModelId()).isEqualTo("anthropic.claude-3-5-sonnet-20241022-v2:0");
        assertThat(response.getConversationHistory()).hasSize(2);
        assertThat(response.getConversationHistory().get(0).getRole()).isEqualTo(ChatMessage.Role.USER);
        assertThat(response.getConversationHistory().get(1).getRole()).isEqualTo(ChatMessage.Role.ASSISTANT);
    }

    @Test
    void chat_multiTurn_appendsToHistory() {
        // Given
        stubBedrockResponse("Yes, it also has many other cities!");

        ChatRequest request = ChatRequest.builder()
                .message("Does France have other famous cities?")
                .conversationHistory(List.of(
                        ChatMessage.builder().role(ChatMessage.Role.USER).content("What is the capital of France?").build(),
                        ChatMessage.builder().role(ChatMessage.Role.ASSISTANT).content("Paris.").build()
                ))
                .build();

        // When
        ChatResponse response = chatService.chat(request);

        // Then
        assertThat(response.getConversationHistory()).hasSize(4);
    }

    @Test
    void chat_perRequestModelOverride_usesOverrideModel() {
        // Given
        stubBedrockResponse("Hello!");

        ChatRequest request = ChatRequest.builder()
                .message("Hi")
                .modelId("anthropic.claude-3-haiku-20240307-v1:0")
                .build();

        // When
        ChatResponse response = chatService.chat(request);

        // Then
        assertThat(response.getModelId()).isEqualTo("anthropic.claude-3-haiku-20240307-v1:0");
    }

    @Test
    void chat_bedrockThrows_wrapsInBedrockException() {
        // Given
        when(bedrockClient.converse(any(ConverseRequest.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        ChatRequest request = ChatRequest.builder().message("Hello").build();

        // Then
        assertThatThrownBy(() -> chatService.chat(request))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("Connection refused");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubBedrockResponse(String replyText) {
        Message assistantMessage = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(ContentBlock.fromText(replyText))
                .build();

        ConverseOutput output = ConverseOutput.builder()
                .message(assistantMessage)
                .build();

        TokenUsage usage = TokenUsage.builder()
                .inputTokens(10)
                .outputTokens(5)
                .totalTokens(15)
                .build();

        when(bedrockClient.converse(any(ConverseRequest.class)))
                .thenReturn(ConverseResponse.builder()
                        .output(output)
                        .usage(usage)
                        .stopReason(StopReason.END_TURN)
                        .build());
    }
}
