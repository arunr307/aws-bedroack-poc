package com.example.bedrock;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.KbQueryRequest;
import com.example.bedrock.model.KbQueryResponse;
import com.example.bedrock.model.KbRetrieveRequest;
import com.example.bedrock.model.KbRetrieveResponse;
import com.example.bedrock.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KnowledgeBaseService}.
 *
 * <p>The {@link BedrockAgentRuntimeClient} is mocked — no real AWS calls are made.
 * Tests verify request construction, response mapping, session handling, and
 * error paths.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    private BedrockAgentRuntimeClient agentRuntimeClient;

    private KnowledgeBaseService service;

    private static final String KB_ID    = "TEST-KB-0001";
    private static final String MODEL_ID = "amazon.nova-lite-v1:0";
    private static final String REGION   = "us-east-1";

    @BeforeEach
    void setUp() {
        BedrockProperties props = new BedrockProperties();
        props.setRegion(REGION);

        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        bedrock.setModelId(MODEL_ID);

        BedrockProperties.KnowledgeBase kb = new BedrockProperties.KnowledgeBase();
        kb.setId(KB_ID);
        kb.setModelId(MODEL_ID);
        kb.setDefaultTopK(5);
        bedrock.setKnowledgeBase(kb);

        props.setBedrock(bedrock);

        service = new KnowledgeBaseService(agentRuntimeClient, props);
    }

    // ── query (RetrieveAndGenerate) ───────────────────────────────────────────

    @Test
    void query_returnsAnswerAndCitations() {
        stubRetrieveAndGenerate(
                "AWS Lambda is a serverless compute service.",
                "sess-001",
                List.of(buildReference(
                        "Lambda is an event-driven, serverless compute platform.",
                        "s3://my-bucket/lambda.pdf",
                        RetrievalResultLocationType.S3)));

        KbQueryResponse response = service.query(KbQueryRequest.builder()
                .question("What is AWS Lambda?")
                .build());

        assertThat(response.getAnswer()).isEqualTo("AWS Lambda is a serverless compute service.");
        assertThat(response.getQuestion()).isEqualTo("What is AWS Lambda?");
        assertThat(response.getSessionId()).isEqualTo("sess-001");
        assertThat(response.getCitations()).hasSize(1);
        assertThat(response.getCitations().get(0).getContent())
                .isEqualTo("Lambda is an event-driven, serverless compute platform.");
        assertThat(response.getCitations().get(0).getSourceUri())
                .isEqualTo("s3://my-bucket/lambda.pdf");
        assertThat(response.getCitations().get(0).getLocationType()).isEqualTo("S3");
        assertThat(response.getCitationCount()).isEqualTo(1);
        assertThat(response.getKnowledgeBaseId()).isEqualTo(KB_ID);
        assertThat(response.getModelId()).isEqualTo(MODEL_ID);
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void query_withSessionId_passesSessionToApi() {
        stubRetrieveAndGenerate("Fargate is serverless containers.", "sess-002", List.of());

        KbQueryResponse response = service.query(KbQueryRequest.builder()
                .question("How does it compare to ECS?")
                .sessionId("sess-001")
                .build());

        // The stub returns sess-002 as the new session; verify we relay it
        assertThat(response.getSessionId()).isEqualTo("sess-002");
    }

    @Test
    void query_withModelOverride_usesSpecifiedModel() {
        stubRetrieveAndGenerate("Answer from pro model.", "sess-003", List.of());

        KbQueryResponse response = service.query(KbQueryRequest.builder()
                .question("Explain VPCs")
                .modelId("amazon.nova-pro-v1:0")
                .build());

        assertThat(response.getModelId()).isEqualTo("amazon.nova-pro-v1:0");
    }

    @Test
    void query_withKbIdOverride_usesOverrideId() {
        stubRetrieveAndGenerate("Custom KB answer.", "sess-004", List.of());

        KbQueryResponse response = service.query(KbQueryRequest.builder()
                .question("Query against a different KB")
                .knowledgeBaseId("OTHER-KB-9999")
                .build());

        assertThat(response.getKnowledgeBaseId()).isEqualTo("OTHER-KB-9999");
    }

    @Test
    void query_multipleCitations_allMapped() {
        stubRetrieveAndGenerate(
                "Lambda and Fargate serve different use cases.",
                "sess-005",
                List.of(
                        buildReference("Lambda runs code...",   "s3://bucket/lambda.pdf", RetrievalResultLocationType.S3),
                        buildReference("Fargate runs containers.", "s3://bucket/fargate.pdf", RetrievalResultLocationType.S3)));

        KbQueryResponse response = service.query(KbQueryRequest.builder()
                .question("Lambda vs Fargate?")
                .build());

        assertThat(response.getCitations()).hasSize(2);
        assertThat(response.getCitationCount()).isEqualTo(2);
    }

    @Test
    void query_agentClientThrows_wrapsInBedrockException() {
        when(agentRuntimeClient.retrieveAndGenerate(any(RetrieveAndGenerateRequest.class)))
                .thenThrow(new RuntimeException("Service unavailable"));

        assertThatThrownBy(() -> service.query(KbQueryRequest.builder()
                .question("Any question")
                .build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("RetrieveAndGenerate failed");
    }

    // ── retrieve ──────────────────────────────────────────────────────────────

    @Test
    void retrieve_returnsRankedChunks() {
        stubRetrieve(List.of(
                buildRetrievalResult("Lambda runs without servers.", 0.95, "s3://bucket/lambda.pdf"),
                buildRetrievalResult("Lambda scales automatically.",  0.87, "s3://bucket/lambda.pdf")));

        KbRetrieveResponse response = service.retrieve(KbRetrieveRequest.builder()
                .query("Lambda serverless compute")
                .build());

        assertThat(response.getQuery()).isEqualTo("Lambda serverless compute");
        assertThat(response.getChunks()).hasSize(2);
        assertThat(response.getChunks().get(0).getScore()).isEqualTo(0.95);
        assertThat(response.getChunks().get(1).getScore()).isEqualTo(0.87);
        assertThat(response.getTotalChunks()).isEqualTo(2);
        assertThat(response.getKnowledgeBaseId()).isEqualTo(KB_ID);
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void retrieve_emptyResults_returnsEmptyChunkList() {
        stubRetrieve(List.of());

        KbRetrieveResponse response = service.retrieve(KbRetrieveRequest.builder()
                .query("Topic not in the knowledge base")
                .build());

        assertThat(response.getChunks()).isEmpty();
        assertThat(response.getTotalChunks()).isZero();
    }

    @Test
    void retrieve_agentClientThrows_wrapsInBedrockException() {
        when(agentRuntimeClient.retrieve(any(RetrieveRequest.class)))
                .thenThrow(new RuntimeException("Timeout"));

        assertThatThrownBy(() -> service.retrieve(KbRetrieveRequest.builder()
                .query("Any query")
                .build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("Retrieve failed");
    }

    // ── Knowledge Base ID validation ──────────────────────────────────────────

    @Test
    void query_noKbIdConfigured_throwsBedrockException() {
        // Service with no KB ID configured
        BedrockProperties props = new BedrockProperties();
        props.setRegion(REGION);
        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        BedrockProperties.KnowledgeBase kb = new BedrockProperties.KnowledgeBase();
        kb.setId("");  // intentionally blank
        kb.setModelId(MODEL_ID);
        kb.setDefaultTopK(5);
        bedrock.setKnowledgeBase(kb);
        props.setBedrock(bedrock);
        KnowledgeBaseService unconfiguredService =
                new KnowledgeBaseService(agentRuntimeClient, props);

        assertThatThrownBy(() -> unconfiguredService.query(KbQueryRequest.builder()
                .question("Will fail")
                .build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("No Knowledge Base ID configured");
    }

    // ── buildModelArn ─────────────────────────────────────────────────────────

    @Test
    void buildModelArn_constructsCorrectArn() {
        String arn = service.buildModelArn("amazon.nova-lite-v1:0");
        assertThat(arn).isEqualTo(
                "arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-lite-v1:0");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubRetrieveAndGenerate(String answer, String sessionId,
                                         List<RetrievedReference> refs) {
        Citation citation = Citation.builder()
                .retrievedReferences(refs)
                .build();

        RetrieveAndGenerateResponse response = RetrieveAndGenerateResponse.builder()
                .output(RetrieveAndGenerateOutput.builder().text(answer).build())
                .sessionId(sessionId)
                .citations(List.of(citation))
                .build();

        when(agentRuntimeClient.retrieveAndGenerate(any(RetrieveAndGenerateRequest.class)))
                .thenReturn(response);
    }

    private void stubRetrieve(List<KnowledgeBaseRetrievalResult> results) {
        RetrieveResponse response = RetrieveResponse.builder()
                .retrievalResults(results)
                .build();
        when(agentRuntimeClient.retrieve(any(RetrieveRequest.class)))
                .thenReturn(response);
    }

    private RetrievedReference buildReference(String text, String s3Uri,
                                               RetrievalResultLocationType type) {
        return RetrievedReference.builder()
                .content(RetrievalResultContent.builder().text(text).build())
                .location(RetrievalResultLocation.builder()
                        .type(type)
                        .s3Location(RetrievalResultS3Location.builder().uri(s3Uri).build())
                        .build())
                .metadata(Map.of(
                        "x-amz-bedrock-kb-source-uri",
                        Document.fromString(s3Uri)))
                .build();
    }

    private KnowledgeBaseRetrievalResult buildRetrievalResult(String text, double score,
                                                               String s3Uri) {
        return KnowledgeBaseRetrievalResult.builder()
                .content(RetrievalResultContent.builder().text(text).build())
                .score(score)
                .location(RetrievalResultLocation.builder()
                        .type(RetrievalResultLocationType.S3)
                        .s3Location(RetrievalResultS3Location.builder().uri(s3Uri).build())
                        .build())
                .metadata(Map.of(
                        "x-amz-bedrock-kb-source-uri",
                        Document.fromString(s3Uri)))
                .build();
    }
}
