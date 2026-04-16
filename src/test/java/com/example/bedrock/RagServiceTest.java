package com.example.bedrock;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.IngestRequest;
import com.example.bedrock.model.IngestResponse;
import com.example.bedrock.model.RagQueryRequest;
import com.example.bedrock.model.RagQueryResponse;
import com.example.bedrock.service.DocumentStore;
import com.example.bedrock.service.EmbeddingService;
import com.example.bedrock.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RagService}.
 *
 * <p>All external dependencies (Bedrock client, EmbeddingService) are mocked so
 * no real AWS calls are made. The {@link DocumentStore} is a real in-memory
 * instance, since its behaviour is integral to the RAG pipeline.
 */
@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock private BedrockRuntimeClient bedrockClient;
    @Mock private EmbeddingService     embeddingService;

    private DocumentStore documentStore;
    private RagService    ragService;

    @BeforeEach
    void setUp() {
        documentStore = new DocumentStore();

        BedrockProperties props = new BedrockProperties();
        props.setRegion("us-east-1");

        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        bedrock.setModelId("amazon.nova-lite-v1:0");
        bedrock.setMaxTokens(2048);
        bedrock.setTemperature(0.7);

        BedrockProperties.Embedding embedding = new BedrockProperties.Embedding();
        embedding.setModelId("amazon.titan-embed-text-v2:0");
        embedding.setDimensions(1024);
        embedding.setNormalize(true);
        bedrock.setEmbedding(embedding);

        props.setBedrock(bedrock);

        ragService = new RagService(bedrockClient, props, embeddingService, documentStore);
    }

    // ── chunkText ─────────────────────────────────────────────────────────────

    @Test
    void chunkText_shortText_returnsOneChunk() {
        String text = "AWS Lambda is serverless compute that runs code without managing servers.";
        List<String> chunks = ragService.chunkText(text, 200, 20);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(text);
    }

    @Test
    void chunkText_longText_producesOverlappingChunks() {
        // Build 300 words: "word_0 word_1 ... word_299"
        String text = IntStream.range(0, 300)
                .mapToObj(i -> "word_" + i)
                .collect(Collectors.joining(" "));

        List<String> chunks = ragService.chunkText(text, 100, 10);

        // step = 90, so we expect: [0..99], [90..189], [180..279], [270..299]
        assertThat(chunks.size()).isGreaterThan(1);

        // First chunk starts at word_0
        assertThat(chunks.get(0)).startsWith("word_0");
        // Second chunk overlaps — should start at word_90
        assertThat(chunks.get(1)).startsWith("word_90");
    }

    @Test
    void chunkText_emptyText_returnsEmptyList() {
        assertThat(ragService.chunkText("", 200, 20)).isEmpty();
        assertThat(ragService.chunkText(null, 200, 20)).isEmpty();
        assertThat(ragService.chunkText("   ", 200, 20)).isEmpty();
    }

    @Test
    void chunkText_overlapClamped_doesNotInfiniteLoop() {
        // overlap >= chunkSize should not loop forever
        String text = "one two three four five";
        List<String> chunks = ragService.chunkText(text, 3, 100);
        assertThat(chunks).isNotEmpty();
    }

    // ── ingest ────────────────────────────────────────────────────────────────

    @Test
    void ingest_singleDocument_storesChunksAndReturnsId() {
        when(embeddingService.embedText(any())).thenReturn(unitVector(1024));

        IngestRequest request = IngestRequest.builder()
                .documents(List.of(
                        IngestRequest.DocumentInput.builder()
                                .title("Lambda Guide")
                                .content("AWS Lambda runs code in response to events. "
                                        + "It scales automatically and you pay per invocation.")
                                .build()))
                .chunkSize(20)
                .chunkOverlap(2)
                .build();

        IngestResponse response = ragService.ingest(request);

        assertThat(response.getDocumentIds()).hasSize(1);
        assertThat(response.getIngestedDocuments()).isEqualTo(1);
        assertThat(response.getTotalChunks()).isGreaterThan(0);
        assertThat(response.getEmbeddingModel()).isEqualTo("amazon.titan-embed-text-v2:0");
        assertThat(response.getTimestamp()).isNotNull();

        // Verify it is actually in the store
        assertThat(documentStore.documentCount()).isEqualTo(1);
        assertThat(documentStore.chunkCount()).isEqualTo(response.getTotalChunks());
    }

    @Test
    void ingest_multipleDocuments_allStoredWithCorrectChunkCounts() {
        when(embeddingService.embedText(any())).thenReturn(unitVector(1024));

        // 5-word content → 1 chunk each with chunkSize=200
        IngestRequest request = IngestRequest.builder()
                .documents(List.of(
                        docInput("Doc A", "Alpha beta gamma delta epsilon"),
                        docInput("Doc B", "Zeta eta theta iota kappa")))
                .build();

        IngestResponse response = ragService.ingest(request);

        assertThat(response.getDocumentIds()).hasSize(2);
        assertThat(response.getIngestedDocuments()).isEqualTo(2);
        assertThat(documentStore.documentCount()).isEqualTo(2);
    }

    @Test
    void ingest_embeddingServiceThrows_propagatesException() {
        when(embeddingService.embedText(any())).thenThrow(new BedrockException("embed failed"));

        IngestRequest request = IngestRequest.builder()
                .documents(List.of(docInput("Doc", "Some content that will be chunked")))
                .build();

        assertThatThrownBy(() -> ragService.ingest(request))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("embed failed");
    }

    // ── query ─────────────────────────────────────────────────────────────────

    @Test
    void query_withMatchingDocument_returnsAnswerAndSources() {
        // Ingest a document
        List<Double> vec = unitVector(1024);
        when(embeddingService.embedText(any())).thenReturn(vec);
        when(embeddingService.similarity(anyList(), anyList())).thenReturn(0.95);

        ragService.ingest(IngestRequest.builder()
                .documents(List.of(docInput("Lambda Docs",
                        "AWS Lambda is serverless. It executes code without managing servers.")))
                .chunkSize(20)
                .chunkOverlap(2)
                .build());

        // Now query
        stubConverseResponse("AWS Lambda is a serverless compute service.", 50, 20);

        RagQueryResponse response = ragService.query(RagQueryRequest.builder()
                .question("What is AWS Lambda?")
                .topK(3)
                .minScore(0.0)
                .build());

        assertThat(response.getAnswer()).isEqualTo("AWS Lambda is a serverless compute service.");
        assertThat(response.getQuestion()).isEqualTo("What is AWS Lambda?");
        assertThat(response.getSources()).isNotEmpty();
        assertThat(response.getRetrievedChunks()).isGreaterThan(0);
        assertThat(response.getGenerationModelId()).isEqualTo("amazon.nova-lite-v1:0");
        assertThat(response.getEmbeddingModelId()).isEqualTo("amazon.titan-embed-text-v2:0");
        assertThat(response.getUsage()).isNotNull();
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void query_noMatchingDocuments_stillCallsModelWithEmptyContext() {
        // No documents ingested — store is empty
        when(embeddingService.embedText(any())).thenReturn(unitVector(1024));
        stubConverseResponse("I don't know based on the provided documents.", 10, 15);

        RagQueryResponse response = ragService.query(RagQueryRequest.builder()
                .question("What is Kubernetes?")
                .topK(5)
                .minScore(0.9)   // high threshold — nothing will match
                .build());

        assertThat(response.getSources()).isEmpty();
        assertThat(response.getRetrievedChunks()).isEqualTo(0);
        assertThat(response.getAnswer()).isNotBlank();
    }

    @Test
    void query_modelOverride_usesSpecifiedModel() {
        when(embeddingService.embedText(any())).thenReturn(unitVector(1024));
        stubConverseResponse("Answer from pro model.", 30, 10);

        RagQueryResponse response = ragService.query(RagQueryRequest.builder()
                .question("Explain VPCs")
                .modelId("amazon.nova-pro-v1:0")
                .topK(5)
                .minScore(0.9)
                .build());

        assertThat(response.getGenerationModelId()).isEqualTo("amazon.nova-pro-v1:0");
    }

    @Test
    void query_bedrockThrows_wrapsInBedrockException() {
        when(embeddingService.embedText(any())).thenReturn(unitVector(1024));
        when(bedrockClient.converse(any(ConverseRequest.class)))
                .thenThrow(new RuntimeException("Bedrock unavailable"));

        assertThatThrownBy(() -> ragService.query(RagQueryRequest.builder()
                .question("Any question")
                .topK(3)
                .minScore(0.9)
                .build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("RAG generation failed");
    }

    @Test
    void query_withCustomSystemPrompt_includesCustomPromptInRequest() {
        when(embeddingService.embedText(any())).thenReturn(unitVector(1024));
        stubConverseResponse("Custom prompt answer.", 20, 10);

        RagQueryResponse response = ragService.query(RagQueryRequest.builder()
                .question("What is ECS?")
                .systemPrompt("You are an AWS expert. Be very concise.")
                .topK(3)
                .minScore(0.9)
                .build());

        // Verify the converse API was called exactly once
        verify(bedrockClient, times(1)).converse(any(ConverseRequest.class));
        assertThat(response.getAnswer()).isEqualTo("Custom prompt answer.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private IngestRequest.DocumentInput docInput(String title, String content) {
        return IngestRequest.DocumentInput.builder().title(title).content(content).build();
    }

    private void stubConverseResponse(String text, int inputTokens, int outputTokens) {
        ContentBlock contentBlock = ContentBlock.fromText(text);
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(contentBlock)
                .build();
        ConverseOutput output = ConverseOutput.builder().message(message).build();
        TokenUsage usage = TokenUsage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .build();
        ConverseResponse converseResponse = ConverseResponse.builder()
                .output(output)
                .usage(usage)
                .stopReason(StopReason.END_TURN)
                .build();
        when(bedrockClient.converse(any(ConverseRequest.class))).thenReturn(converseResponse);
    }

    private List<Double> unitVector(int dims) {
        List<Double> v = new ArrayList<>(dims);
        v.add(1.0);
        for (int i = 1; i < dims; i++) v.add(0.0);
        return v;
    }
}
