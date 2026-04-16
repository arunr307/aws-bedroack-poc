package com.example.bedrock;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.SummarizeRequest;
import com.example.bedrock.model.SummarizeRequest.SummaryStyle;
import com.example.bedrock.model.SummarizeResponse;
import com.example.bedrock.service.SummarizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SummarizationService}.
 *
 * <p>The Bedrock client is mocked — no real AWS calls are made.
 */
@ExtendWith(MockitoExtension.class)
class SummarizationServiceTest {

    @Mock
    private BedrockRuntimeClient bedrockClient;

    private SummarizationService service;

    private static final String SAMPLE_TEXT =
            "Amazon Bedrock is a fully managed service that makes foundation models " +
            "from Amazon and leading AI companies available via an API, so you can " +
            "choose from a wide range of foundation models to find the model that is " +
            "best suited for your use case. Amazon Bedrock offers a serverless " +
            "experience so you can get started quickly, privately customize foundation " +
            "models with your own data, and integrate and deploy them into your " +
            "applications using AWS tools and capabilities.";

    @BeforeEach
    void setUp() {
        BedrockProperties props = new BedrockProperties();
        props.setRegion("us-east-1");

        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        bedrock.setModelId("amazon.nova-lite-v1:0");
        bedrock.setMaxTokens(2048);
        bedrock.setTemperature(0.7);
        props.setBedrock(bedrock);

        service = new SummarizationService(bedrockClient, props);
    }

    @Test
    void summarize_brief_returnsSummaryWithMetrics() {
        stubBedrockResponse("Amazon Bedrock is a managed service for foundation models via API.");

        SummarizeRequest request = SummarizeRequest.builder()
                .text(SAMPLE_TEXT)
                .style(SummaryStyle.BRIEF)
                .build();

        SummarizeResponse response = service.summarize(request);

        assertThat(response.getSummary()).isNotBlank();
        assertThat(response.getStyle()).isEqualTo(SummaryStyle.BRIEF);
        assertThat(response.getOriginalWordCount()).isGreaterThan(0);
        assertThat(response.getSummaryWordCount()).isGreaterThan(0);
        assertThat(response.getCompressionRatio()).isGreaterThan(1.0);
        assertThat(response.getModelId()).isEqualTo("amazon.nova-lite-v1:0");
        assertThat(response.getTimestamp()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(SummaryStyle.class)
    void summarize_allStyles_succeed(SummaryStyle style) {
        stubBedrockResponse("Summary for style: " + style.name());

        SummarizeRequest request = SummarizeRequest.builder()
                .text(SAMPLE_TEXT)
                .style(style)
                .build();

        SummarizeResponse response = service.summarize(request);

        assertThat(response.getStyle()).isEqualTo(style);
        assertThat(response.getSummary()).contains(style.name());
    }

    @Test
    void summarize_perRequestModelOverride_usesOverrideModel() {
        stubBedrockResponse("Brief summary.");

        SummarizeRequest request = SummarizeRequest.builder()
                .text(SAMPLE_TEXT)
                .modelId("amazon.nova-pro-v1:0")
                .build();

        SummarizeResponse response = service.summarize(request);

        assertThat(response.getModelId()).isEqualTo("amazon.nova-pro-v1:0");
    }

    @Test
    void summarize_withLanguageAndFocus_returnsSummary() {
        stubBedrockResponse("Resumen: Amazon Bedrock es un servicio gestionado.");

        SummarizeRequest request = SummarizeRequest.builder()
                .text(SAMPLE_TEXT)
                .style(SummaryStyle.BRIEF)
                .language("Spanish")
                .focusOn("serverless capabilities")
                .maxWords(50)
                .build();

        SummarizeResponse response = service.summarize(request);

        assertThat(response.getSummary()).isNotBlank();
    }

    @Test
    void summarize_bedrockThrows_wrapsInBedrockException() {
        when(bedrockClient.converse(any(ConverseRequest.class)))
                .thenThrow(new RuntimeException("Service unavailable"));

        SummarizeRequest request = SummarizeRequest.builder()
                .text(SAMPLE_TEXT)
                .build();

        assertThatThrownBy(() -> service.summarize(request))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("Service unavailable");
    }

    @Test
    void summarize_compressionRatio_isCalculatedCorrectly() {
        // 5-word summary for a ~80-word input → ratio ≈ 16
        stubBedrockResponse("Short five word summary.");

        SummarizeRequest request = SummarizeRequest.builder()
                .text(SAMPLE_TEXT)
                .style(SummaryStyle.HEADLINE)
                .build();

        SummarizeResponse response = service.summarize(request);

        assertThat(response.getCompressionRatio()).isGreaterThan(1.0);
        assertThat(response.getOriginalWordCount()).isGreaterThan(response.getSummaryWordCount());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void stubBedrockResponse(String text) {
        Message assistantMessage = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(ContentBlock.fromText(text))
                .build();

        when(bedrockClient.converse(any(ConverseRequest.class)))
                .thenReturn(ConverseResponse.builder()
                        .output(ConverseOutput.builder().message(assistantMessage).build())
                        .usage(TokenUsage.builder()
                                .inputTokens(200)
                                .outputTokens(20)
                                .totalTokens(220)
                                .build())
                        .stopReason(StopReason.END_TURN)
                        .build());
    }
}
