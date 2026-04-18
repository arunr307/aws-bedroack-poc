package com.example.bedrock;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.ImageGenerateRequest;
import com.example.bedrock.model.ImageGenerateResponse;
import com.example.bedrock.model.ImageVariationRequest;
import com.example.bedrock.service.ImageGenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ImageGenerationService}.
 *
 * <p>The Bedrock client is mocked with pre-built Titan/Stability AI response payloads
 * so no real AWS calls are made.
 */
@ExtendWith(MockitoExtension.class)
class ImageGenerationServiceTest {

    @Mock
    private BedrockRuntimeClient bedrockClient;

    private ImageGenerationService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** A tiny valid-looking base64 string used as a fake image in tests. */
    private static final String FAKE_IMAGE_B64 =
            Base64.getEncoder().encodeToString("fake-png-bytes".getBytes());

    @BeforeEach
    void setUp() {
        BedrockProperties props = new BedrockProperties();
        props.setRegion("us-east-1");

        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        bedrock.setModelId("amazon.nova-lite-v1:0");

        BedrockProperties.Image image = new BedrockProperties.Image();
        image.setModelId("amazon.titan-image-generator-v2:0");
        image.setDefaultWidth(1024);
        image.setDefaultHeight(1024);
        image.setDefaultNumberOfImages(1);
        image.setDefaultCfgScale(8.0);
        image.setDefaultQuality("standard");
        bedrock.setImage(image);

        props.setBedrock(bedrock);

        service = new ImageGenerationService(bedrockClient, props, objectMapper);
    }

    // ── generateImage — Titan ─────────────────────────────────────────────────

    @Test
    void generateImage_titan_returnsImages() throws Exception {
        stubTitanResponse(List.of(FAKE_IMAGE_B64));

        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .prompt("A beautiful sunset")
                .build();

        ImageGenerateResponse response = service.generateImage(request);

        assertThat(response.getImages()).hasSize(1);
        assertThat(response.getImages().get(0)).isEqualTo(FAKE_IMAGE_B64);
        assertThat(response.getImagesGenerated()).isEqualTo(1);
        assertThat(response.getModelId()).isEqualTo("amazon.titan-image-generator-v2:0");
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void generateImage_titan_multipleImages() throws Exception {
        stubTitanResponse(List.of(FAKE_IMAGE_B64, FAKE_IMAGE_B64, FAKE_IMAGE_B64));

        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .prompt("Three variations of a mountain landscape")
                .numberOfImages(3)
                .build();

        ImageGenerateResponse response = service.generateImage(request);

        assertThat(response.getImages()).hasSize(3);
        assertThat(response.getImagesGenerated()).isEqualTo(3);
    }

    @Test
    void generateImage_titan_withNegativePrompt_succeeds() throws Exception {
        stubTitanResponse(List.of(FAKE_IMAGE_B64));

        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .prompt("A serene beach")
                .negativePrompt("people, crowds, garbage")
                .width(512)
                .height(512)
                .cfgScale(9.0)
                .quality("premium")
                .build();

        ImageGenerateResponse response = service.generateImage(request);

        assertThat(response.getImages()).hasSize(1);
    }

    @Test
    void generateImage_titan_withSeed_succeeds() throws Exception {
        stubTitanResponse(List.of(FAKE_IMAGE_B64));

        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .prompt("Reproducible image")
                .seed(12345L)
                .build();

        ImageGenerateResponse response = service.generateImage(request);

        assertThat(response.getImages()).isNotEmpty();
    }

    @Test
    void generateImage_modelOverride_usesTitanV1() throws Exception {
        stubTitanResponse(List.of(FAKE_IMAGE_B64));

        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .prompt("Test prompt")
                .modelId("amazon.titan-image-generator-v1")
                .build();

        ImageGenerateResponse response = service.generateImage(request);

        assertThat(response.getModelId()).isEqualTo("amazon.titan-image-generator-v1");
    }

    // ── generateImage — Stability AI ─────────────────────────────────────────

    @Test
    void generateImage_stabilityAi_returnsImages() throws Exception {
        stubStabilityResponse(FAKE_IMAGE_B64);

        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .prompt("Cinematic sci-fi cityscape")
                .modelId("stability.stable-diffusion-xl-v1")
                .build();

        ImageGenerateResponse response = service.generateImage(request);

        assertThat(response.getImages()).hasSize(1);
        assertThat(response.getImages().get(0)).isEqualTo(FAKE_IMAGE_B64);
        assertThat(response.getModelId()).isEqualTo("stability.stable-diffusion-xl-v1");
    }

    @Test
    void generateImage_stabilityAi_errorArtifact_throwsException() throws Exception {
        stubStabilityErrorResponse();

        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .prompt("Bad prompt")
                .modelId("stability.stable-diffusion-xl-v1")
                .build();

        assertThatThrownBy(() -> service.generateImage(request))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("error");
    }

    // ── generateVariation ─────────────────────────────────────────────────────

    @Test
    void generateVariation_titan_returnsVariations() throws Exception {
        stubTitanResponse(List.of(FAKE_IMAGE_B64));

        ImageVariationRequest request = ImageVariationRequest.builder()
                .inputImageBase64(FAKE_IMAGE_B64)
                .prompt("Same scene but at night")
                .similarityStrength(0.7)
                .build();

        ImageGenerateResponse response = service.generateVariation(request);

        assertThat(response.getImages()).hasSize(1);
        assertThat(response.getImagesGenerated()).isEqualTo(1);
    }

    @Test
    void generateVariation_stabilityModel_throwsUnsupportedException() {
        ImageVariationRequest request = ImageVariationRequest.builder()
                .inputImageBase64(FAKE_IMAGE_B64)
                .modelId("stability.stable-diffusion-xl-v1")
                .build();

        assertThatThrownBy(() -> service.generateVariation(request))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("not supported for Stability AI");
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void generateImage_titanErrorField_throwsBedrockException() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("error", "Content policy violation");

        stubRawResponse(body);

        assertThatThrownBy(() -> service.generateImage(
                ImageGenerateRequest.builder().prompt("Bad content").build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("Content policy violation");
    }

    @Test
    void generateImage_bedrockClientThrows_wrapsException() {
        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenThrow(new RuntimeException("Connection timeout"));

        assertThatThrownBy(() -> service.generateImage(
                ImageGenerateRequest.builder().prompt("test").build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("Connection timeout");
    }

    @Test
    void generateImage_titanNoImagesArray_throwsException() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("someOtherField", "unexpected");

        stubRawResponse(body);

        assertThatThrownBy(() -> service.generateImage(
                ImageGenerateRequest.builder().prompt("test").build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("no 'images' array");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubTitanResponse(List<String> base64Images) throws Exception {
        ObjectNode body   = objectMapper.createObjectNode();
        ArrayNode  images = body.putArray("images");
        base64Images.forEach(images::add);
        body.putNull("error");

        stubRawResponse(body);
    }

    private void stubStabilityResponse(String base64Image) throws Exception {
        ObjectNode body      = objectMapper.createObjectNode();
        body.put("result", "success");
        ArrayNode  artifacts = body.putArray("artifacts");
        ObjectNode artifact  = artifacts.addObject();
        artifact.put("base64",       base64Image);
        artifact.put("finishReason", "SUCCESS");
        artifact.put("seed",         98765);

        stubRawResponse(body);
    }

    private void stubStabilityErrorResponse() throws Exception {
        ObjectNode body      = objectMapper.createObjectNode();
        body.put("result", "success");
        ArrayNode  artifacts = body.putArray("artifacts");
        ObjectNode artifact  = artifacts.addObject();
        artifact.put("base64",       "");
        artifact.put("finishReason", "ERROR");

        stubRawResponse(body);
    }

    private void stubRawResponse(ObjectNode body) throws Exception {
        InvokeModelResponse response = InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(body)))
                .contentType("application/json")
                .build();

        when(bedrockClient.invokeModel(any(InvokeModelRequest.class))).thenReturn(response);
    }
}
