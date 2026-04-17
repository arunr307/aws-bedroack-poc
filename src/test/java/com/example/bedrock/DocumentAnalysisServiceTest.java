package com.example.bedrock;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.AnalysisRequest;
import com.example.bedrock.model.AnalysisResponse;
import com.example.bedrock.model.AnalysisType;
import com.example.bedrock.service.DocumentAnalysisService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Unit tests for {@link DocumentAnalysisService}.
 *
 * <p>The Bedrock client is mocked with pre-built JSON responses,
 * so no real AWS calls are made.
 */
@ExtendWith(MockitoExtension.class)
class DocumentAnalysisServiceTest {

    @Mock
    private BedrockRuntimeClient bedrockClient;

    private DocumentAnalysisService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SAMPLE_TEXT =
            "Apple Inc. reported record revenue of $89.5B in Q1 2024, beating analyst " +
            "expectations. CEO Tim Cook cited strong iPhone 15 sales and growth in " +
            "services revenue. The company also announced a $110B share buyback programme.";

    @BeforeEach
    void setUp() {
        BedrockProperties props = new BedrockProperties();
        props.setRegion("us-east-1");

        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        bedrock.setModelId("amazon.nova-lite-v1:0");
        bedrock.setMaxTokens(4096);
        props.setBedrock(bedrock);

        service = new DocumentAnalysisService(bedrockClient, props, objectMapper);
    }

    // ── analyze — full response ───────────────────────────────────────────────

    @Test
    void analyze_allTypes_populatesAllSections() throws Exception {
        stubConverseResponse(fullJsonResponse());

        AnalysisRequest request = AnalysisRequest.builder().text(SAMPLE_TEXT).build();
        AnalysisResponse response = service.analyze(request);

        // All five types should be present
        assertThat(response.getAnalysisTypes()).containsExactlyInAnyOrder(AnalysisType.values());

        // Sentiment
        assertThat(response.getSentiment()).isNotNull();
        assertThat(response.getSentiment().getLabel()).isEqualTo("POSITIVE");
        assertThat(response.getSentiment().getConfidence()).isGreaterThan(0);

        // Entities
        assertThat(response.getEntities()).isNotNull().isNotEmpty();
        assertThat(response.getEntities().get(0).getText()).isEqualTo("Apple Inc.");
        assertThat(response.getEntities().get(0).getType()).isEqualTo("ORGANIZATION");

        // Key phrases
        assertThat(response.getKeyPhrases()).isNotNull().isNotEmpty();
        assertThat(response.getKeyPhrases().get(0).getPhrase()).isEqualTo("record revenue");

        // Classification
        assertThat(response.getClassifications()).isNotNull().isNotEmpty();
        assertThat(response.getClassifications().get(0).getLabel()).isEqualTo("FINANCE");

        // Language
        assertThat(response.getLanguage()).isNotNull();
        assertThat(response.getLanguage().getLanguageCode()).isEqualTo("en");
        assertThat(response.getLanguage().getLanguageName()).isEqualTo("English");

        // Metadata
        assertThat(response.getModelId()).isEqualTo("amazon.nova-lite-v1:0");
        assertThat(response.getUsage()).isNotNull();
        assertThat(response.getTimestamp()).isNotNull();
    }

    // ── analyze — selective types ─────────────────────────────────────────────

    @Test
    void analyze_sentimentOnly_otherSectionsAreNull() throws Exception {
        stubConverseResponse(sentimentOnlyJson());

        AnalysisRequest request = AnalysisRequest.builder()
                .text(SAMPLE_TEXT)
                .analysisTypes(List.of(AnalysisType.SENTIMENT))
                .build();

        AnalysisResponse response = service.analyze(request);

        assertThat(response.getAnalysisTypes()).containsExactly(AnalysisType.SENTIMENT);
        assertThat(response.getSentiment()).isNotNull();
        assertThat(response.getEntities()).isNull();
        assertThat(response.getKeyPhrases()).isNull();
        assertThat(response.getClassifications()).isNull();
        assertThat(response.getLanguage()).isNull();
    }

    @Test
    void analyze_entitiesOnly_returnsEntityList() throws Exception {
        stubConverseResponse(entitiesOnlyJson());

        AnalysisRequest request = AnalysisRequest.builder()
                .text(SAMPLE_TEXT)
                .analysisTypes(List.of(AnalysisType.ENTITIES))
                .build();

        AnalysisResponse response = service.analyze(request);

        assertThat(response.getEntities()).hasSize(3);
        assertThat(response.getSentiment()).isNull();
    }

    @Test
    void analyze_classificationWithCustomLabels_returnsMatchedLabel() throws Exception {
        stubConverseResponse(customClassificationJson());

        AnalysisRequest request = AnalysisRequest.builder()
                .text("There is a bug causing the login page to crash on mobile.")
                .analysisTypes(List.of(AnalysisType.CLASSIFICATION))
                .customLabels(List.of("Bug Report", "Feature Request", "General Inquiry"))
                .build();

        AnalysisResponse response = service.analyze(request);

        assertThat(response.getClassifications()).isNotNull().isNotEmpty();
        assertThat(response.getClassifications().get(0).getLabel()).isEqualTo("Bug Report");
        assertThat(response.getClassifications().get(0).getScore()).isGreaterThan(0.8);
    }

    @Test
    void analyze_languageDetectionOnly_returnsLanguageResult() throws Exception {
        stubConverseResponse(languageOnlyJson());

        AnalysisRequest request = AnalysisRequest.builder()
                .text("Bonjour le monde, ceci est un test.")
                .analysisTypes(List.of(AnalysisType.LANGUAGE_DETECTION))
                .build();

        AnalysisResponse response = service.analyze(request);

        assertThat(response.getLanguage()).isNotNull();
        assertThat(response.getLanguage().getLanguageCode()).isEqualTo("fr");
        assertThat(response.getLanguage().getLanguageName()).isEqualTo("French");
        assertThat(response.getLanguage().getConfidence()).isGreaterThan(0.9);
    }

    @Test
    void analyze_modelOverride_usesSpecifiedModel() throws Exception {
        stubConverseResponse(sentimentOnlyJson());

        AnalysisRequest request = AnalysisRequest.builder()
                .text(SAMPLE_TEXT)
                .analysisTypes(List.of(AnalysisType.SENTIMENT))
                .modelId("amazon.nova-pro-v1:0")
                .build();

        AnalysisResponse response = service.analyze(request);

        assertThat(response.getModelId()).isEqualTo("amazon.nova-pro-v1:0");
    }

    // ── analyze — error handling ──────────────────────────────────────────────

    @Test
    void analyze_bedrockThrows_wrapsInBedrockException() {
        when(bedrockClient.converse(any(ConverseRequest.class)))
                .thenThrow(new RuntimeException("Network timeout"));

        assertThatThrownBy(() -> service.analyze(
                AnalysisRequest.builder().text(SAMPLE_TEXT).build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("Document analysis call failed");
    }

    // ── parseModelOutput ──────────────────────────────────────────────────────

    @Test
    void parseModelOutput_plainJson_parsesSuccessfully() throws Exception {
        String json = sentimentOnlyJson();
        AnalysisResponse.ModelOutput output = service.parseModelOutput(json);
        assertThat(output.getSentiment()).isNotNull();
        assertThat(output.getSentiment().getLabel()).isEqualTo("POSITIVE");
    }

    @Test
    void parseModelOutput_fencedJson_stripsFencesAndParses() throws Exception {
        String fenced = "```json\n" + sentimentOnlyJson() + "\n```";
        AnalysisResponse.ModelOutput output = service.parseModelOutput(fenced);
        assertThat(output.getSentiment()).isNotNull();
        assertThat(output.getSentiment().getLabel()).isEqualTo("POSITIVE");
    }

    @Test
    void parseModelOutput_fencedWithoutLanguageTag_stripsFencesAndParses() throws Exception {
        String fenced = "```\n" + sentimentOnlyJson() + "\n```";
        AnalysisResponse.ModelOutput output = service.parseModelOutput(fenced);
        assertThat(output.getSentiment()).isNotNull();
    }

    @Test
    void parseModelOutput_trashInput_throwsBedrockException() {
        assertThatThrownBy(() -> service.parseModelOutput("This is not JSON at all!"))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("unparseable JSON");
    }

    @Test
    void parseModelOutput_unknownFieldsIgnored_doesNotThrow() throws Exception {
        // Model adds extra fields → should be ignored gracefully
        String jsonWithExtra = """
                {
                  "sentiment": {"label":"NEUTRAL","confidence":0.8,
                    "positiveScore":0.1,"negativeScore":0.1,
                    "neutralScore":0.8,"mixedScore":0.0},
                  "unexpectedField": "ignored"
                }
                """;
        AnalysisResponse.ModelOutput output = service.parseModelOutput(jsonWithExtra);
        assertThat(output.getSentiment().getLabel()).isEqualTo("NEUTRAL");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubConverseResponse(String jsonText) {
        ContentBlock block = ContentBlock.fromText(jsonText);
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(block)
                .build();
        ConverseOutput output = ConverseOutput.builder().message(message).build();
        TokenUsage usage = TokenUsage.builder()
                .inputTokens(400).outputTokens(200).totalTokens(600).build();
        ConverseResponse response = ConverseResponse.builder()
                .output(output).usage(usage).stopReason(StopReason.END_TURN).build();
        when(bedrockClient.converse(any(ConverseRequest.class))).thenReturn(response);
    }

    private String fullJsonResponse() {
        return """
                {
                  "sentiment": {
                    "label": "POSITIVE",
                    "confidence": 0.91,
                    "positiveScore": 0.82,
                    "negativeScore": 0.03,
                    "neutralScore":  0.12,
                    "mixedScore":    0.03
                  },
                  "entities": [
                    { "text": "Apple Inc.", "type": "ORGANIZATION", "confidence": 0.99 },
                    { "text": "Tim Cook",   "type": "PERSON",       "confidence": 0.98 },
                    { "text": "$89.5B",     "type": "MONEY",        "confidence": 0.97 }
                  ],
                  "keyPhrases": [
                    { "phrase": "record revenue",       "score": 0.95 },
                    { "phrase": "strong iPhone 15 sales","score": 0.91 },
                    { "phrase": "share buyback",         "score": 0.88 }
                  ],
                  "classifications": [
                    { "label": "FINANCE",    "score": 0.88 },
                    { "label": "TECHNOLOGY", "score": 0.72 }
                  ],
                  "language": {
                    "languageCode": "en",
                    "languageName": "English",
                    "confidence": 0.99
                  }
                }
                """;
    }

    private String sentimentOnlyJson() {
        return """
                {
                  "sentiment": {
                    "label": "POSITIVE",
                    "confidence": 0.91,
                    "positiveScore": 0.82,
                    "negativeScore": 0.03,
                    "neutralScore":  0.12,
                    "mixedScore":    0.03
                  }
                }
                """;
    }

    private String entitiesOnlyJson() {
        return """
                {
                  "entities": [
                    { "text": "Apple Inc.", "type": "ORGANIZATION", "confidence": 0.99 },
                    { "text": "Tim Cook",   "type": "PERSON",       "confidence": 0.98 },
                    { "text": "$89.5B",     "type": "MONEY",        "confidence": 0.97 }
                  ]
                }
                """;
    }

    private String customClassificationJson() {
        return """
                {
                  "classifications": [
                    { "label": "Bug Report",      "score": 0.94 },
                    { "label": "Feature Request", "score": 0.12 }
                  ]
                }
                """;
    }

    private String languageOnlyJson() {
        return """
                {
                  "language": {
                    "languageCode": "fr",
                    "languageName": "French",
                    "confidence": 0.99
                  }
                }
                """;
    }
}
