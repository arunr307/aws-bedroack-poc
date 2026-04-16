package com.example.bedrock;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.*;
import com.example.bedrock.service.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmbeddingService}.
 *
 * <p>The Bedrock client is mocked with pre-built embedding vectors
 * so no real AWS calls are made.
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private BedrockRuntimeClient bedrockClient;

    private EmbeddingService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        BedrockProperties props = new BedrockProperties();
        props.setRegion("us-east-1");

        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        bedrock.setModelId("amazon.nova-lite-v1:0");
        bedrock.setMaxTokens(2048);

        BedrockProperties.Embedding embedding = new BedrockProperties.Embedding();
        embedding.setModelId("amazon.titan-embed-text-v2:0");
        embedding.setDimensions(1024);
        embedding.setNormalize(true);
        bedrock.setEmbedding(embedding);

        props.setBedrock(bedrock);

        service = new EmbeddingService(bedrockClient, props, objectMapper);
    }

    // ── embed ─────────────────────────────────────────────────────────────────

    @Test
    void embed_returnsVectorWithCorrectDimensions() throws Exception {
        List<Double> fakeVector = unitVector(1024);
        stubEmbeddingResponse(fakeVector, 10);

        EmbedRequest request = EmbedRequest.builder()
                .text("AWS Lambda is serverless compute.")
                .build();

        EmbedResponse response = service.embed(request);

        assertThat(response.getEmbedding()).hasSize(1024);
        assertThat(response.getDimensions()).isEqualTo(1024);
        assertThat(response.getInputTokenCount()).isEqualTo(10);
        assertThat(response.getModelId()).isEqualTo("amazon.titan-embed-text-v2:0");
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void embed_withDimensionOverride_usesOverride() throws Exception {
        stubEmbeddingResponse(unitVector(512), 8);

        EmbedRequest request = EmbedRequest.builder()
                .text("Some text")
                .dimensions(512)
                .build();

        EmbedResponse response = service.embed(request);

        assertThat(response.getDimensions()).isEqualTo(512);
    }

    @Test
    void embed_withModelOverride_usesOverrideModel() throws Exception {
        stubEmbeddingResponse(unitVector(1024), 5);

        EmbedRequest request = EmbedRequest.builder()
                .text("Some text")
                .modelId("amazon.titan-embed-text-v1")
                .build();

        EmbedResponse response = service.embed(request);

        assertThat(response.getModelId()).isEqualTo("amazon.titan-embed-text-v1");
    }

    // ── similarity ────────────────────────────────────────────────────────────

    @Test
    void similarity_identicalVectors_scoresOne() throws Exception {
        // Same vector returned for both texts → cosine similarity = 1.0
        stubEmbeddingResponseAlways(unitVector(1024), 7);

        SimilarityRequest request = SimilarityRequest.builder()
                .textA("AWS Lambda is serverless.")
                .textB("AWS Lambda is serverless.")
                .build();

        SimilarityResponse response = service.similarity(request);

        assertThat(response.getScore()).isEqualTo(1.0);
        assertThat(response.getInterpretation()).isEqualTo("Very similar");
    }

    @Test
    void similarity_orthogonalVectors_scoresZero() throws Exception {
        // Two orthogonal unit vectors → cosine similarity = 0
        List<Double> vecA = zeroVector(1024);
        vecA.set(0, 1.0);                  // [1, 0, 0, ...]
        List<Double> vecB = zeroVector(1024);
        vecB.set(1, 1.0);                  // [0, 1, 0, ...]

        stubEmbeddingResponseSequence(List.of(vecA, vecB), 5);

        SimilarityResponse response = service.similarity(
                SimilarityRequest.builder().textA("text A").textB("text B").build());

        assertThat(response.getScore()).isEqualTo(0.0);
        assertThat(response.getInterpretation()).isEqualTo("Unrelated / opposite");
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void search_returnsTopKRankedResults() throws Exception {
        // Query: [1,0,...], docs: [1,0,...] score=1.0, [0,1,...] score=0.0, [0.7,0.7,...] score~0.7
        List<Double> queryVec = zeroVector(1024); queryVec.set(0, 1.0);
        List<Double> docA     = zeroVector(1024); docA.set(0, 1.0);
        List<Double> docB     = zeroVector(1024); docB.set(1, 1.0);
        List<Double> docC     = zeroVector(1024); docC.set(0, 0.707); docC.set(1, 0.707);

        stubEmbeddingResponseSequence(List.of(queryVec, docA, docB, docC), 5);

        SemanticSearchRequest request = SemanticSearchRequest.builder()
                .query("serverless compute")
                .documents(List.of("Lambda", "S3", "Fargate"))
                .topK(2)
                .build();

        SemanticSearchResponse response = service.search(request);

        assertThat(response.getReturnedResults()).isEqualTo(2);
        assertThat(response.getTotalDocuments()).isEqualTo(3);
        assertThat(response.getResults().get(0).getRank()).isEqualTo(1);
        assertThat(response.getResults().get(0).getScore()).isGreaterThan(
                response.getResults().get(1).getScore());
    }

    @Test
    void search_minScoreFilter_excludesLowScoringResults() throws Exception {
        List<Double> queryVec = zeroVector(1024); queryVec.set(0, 1.0);
        List<Double> docHigh  = zeroVector(1024); docHigh.set(0, 1.0);
        List<Double> docLow   = zeroVector(1024); docLow.set(1, 1.0);  // score = 0

        stubEmbeddingResponseSequence(List.of(queryVec, docHigh, docLow), 5);

        SemanticSearchRequest request = SemanticSearchRequest.builder()
                .query("serverless")
                .documents(List.of("Lambda", "S3"))
                .topK(5)
                .minScore(0.5)
                .build();

        SemanticSearchResponse response = service.search(request);

        // Only docHigh should pass the minScore=0.5 filter
        assertThat(response.getReturnedResults()).isEqualTo(1);
        assertThat(response.getResults().get(0).getDocument()).isEqualTo("Lambda");
    }

    @Test
    void embed_bedrockThrows_wrapsInBedrockException() {
        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> service.embed(
                EmbedRequest.builder().text("test").build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("Connection refused");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Stubs every InvokeModel call with the same vector. */
    private void stubEmbeddingResponse(List<Double> vector, int tokens) throws Exception {
        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(buildResponse(vector, tokens));
    }

    private void stubEmbeddingResponseAlways(List<Double> vector, int tokens) throws Exception {
        stubEmbeddingResponse(vector, tokens);
    }

    /** Stubs InvokeModel calls to return vectors in sequence. */
    private void stubEmbeddingResponseSequence(List<List<Double>> vectors, int tokens) throws Exception {
        var responses = vectors.stream()
                .map(v -> {
                    try { return buildResponse(v, tokens); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .toArray(InvokeModelResponse[]::new);

        var first  = responses[0];
        var rest   = new InvokeModelResponse[responses.length - 1];
        System.arraycopy(responses, 1, rest, 0, rest.length);

        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(first, rest);
    }

    private InvokeModelResponse buildResponse(List<Double> vector, int tokens) throws Exception {
        var body = objectMapper.createObjectNode();
        var arr  = body.putArray("embedding");
        vector.forEach(arr::add);
        body.put("inputTextTokenCount", tokens);

        return InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(body)))
                .contentType("application/json")
                .build();
    }

    /** Creates a unit vector pointing along the first axis (normalised). */
    private List<Double> unitVector(int dims) {
        List<Double> v = zeroVector(dims);
        v.set(0, 1.0);
        return v;
    }

    private List<Double> zeroVector(int dims) {
        return IntStream.range(0, dims)
                .mapToObj(i -> 0.0)
                .collect(Collectors.toCollection(java.util.ArrayList::new));
    }
}
