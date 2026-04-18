package com.example.bedrock.service;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.ImageGenerateRequest;
import com.example.bedrock.model.ImageGenerateResponse;
import com.example.bedrock.model.ImageVariationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates images using Amazon Bedrock image models via the {@code InvokeModel} API.
 *
 * <h2>Supported models</h2>
 * <ul>
 *   <li><strong>Amazon Nova Canvas</strong>
 *       ({@code amazon.nova-canvas-v1:0}) — default, current generation, no form needed.
 *       Supports TEXT_IMAGE and IMAGE_VARIATION tasks.</li>
 *   <li><strong>Stability AI SDXL</strong>
 *       ({@code stability.stable-diffusion-xl-v1}) — requires separate model-access form.
 *       Uses a different payload format; handled transparently by this service.</li>
 * </ul>
 *
 * <h2>Payload formats</h2>
 * <p>Amazon image models (Nova Canvas, Titan) share the same JSON schema.
 * Stability AI uses a different schema. This service detects the model family
 * from the model ID prefix and routes to the correct builder/parser.
 *
 * <h2>Response images</h2>
 * <p>All models return base64-encoded PNG strings. Callers can decode with
 * {@code Base64.getDecoder().decode(image)} and write to a {@code .png} file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private final BedrockRuntimeClient bedrockClient;
    private final BedrockProperties    properties;
    private final ObjectMapper         objectMapper;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates images from a text prompt.
     *
     * @param request generation parameters (prompt required, all others optional)
     * @return response containing base64-encoded PNG images
     */
    public ImageGenerateResponse generateImage(ImageGenerateRequest request) {
        String modelId = resolveModelId(request.getModelId());
        log.debug("Generating image — modelId={}, prompt='{}'", modelId, request.getPrompt());

        ObjectNode      payload  = buildTextToImagePayload(request, modelId);
        InvokeModelResponse raw  = invokeModel(modelId, payload);
        List<String>    images   = extractImages(raw, modelId);

        log.debug("Generated {} image(s) with modelId={}", images.size(), modelId);

        return ImageGenerateResponse.builder()
                .images(images)
                .imagesGenerated(images.size())
                .modelId(modelId)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Generates variations of an existing image.
     * <p>Supported by Titan Image Generator (V1 and V2). Stability AI models do not
     * support this task — use {@link #generateImage} with a descriptive prompt instead.
     *
     * @param request variation parameters (inputImageBase64 required)
     * @return response containing base64-encoded PNG variations
     */
    public ImageGenerateResponse generateVariation(ImageVariationRequest request) {
        String modelId = resolveModelId(request.getModelId());
        log.debug("Generating image variation — modelId={}", modelId);

        if (isStabilityModel(modelId)) {
            throw new BedrockException(
                    "Image variation is not supported for Stability AI models. "
                    + "Use POST /api/images/generate with a descriptive prompt instead.");
        }

        ObjectNode      payload = buildImageVariationPayload(request, modelId);
        InvokeModelResponse raw = invokeModel(modelId, payload);
        List<String>    images  = extractImages(raw, modelId);

        log.debug("Generated {} variation(s) with modelId={}", images.size(), modelId);

        return ImageGenerateResponse.builder()
                .images(images)
                .imagesGenerated(images.size())
                .modelId(modelId)
                .timestamp(Instant.now())
                .build();
    }

    // ── Payload builders ──────────────────────────────────────────────────────

    private ObjectNode buildTextToImagePayload(ImageGenerateRequest request, String modelId) {
        return isStabilityModel(modelId)
                ? buildStabilityTextPayload(request)
                : buildAmazonTextToImagePayload(request, modelId);
    }

    /**
     * Builds the Amazon image model TEXT_IMAGE payload.
     *
     * <p>Works for:
     * <ul>
     *   <li><strong>Nova Canvas</strong> ({@code amazon.nova-canvas-v1:0}) — current generation.
     *       The {@code quality} field is <em>not</em> supported and is intentionally omitted.</li>
     *   <li><strong>Titan Image Generator V2</strong> ({@code amazon.titan-image-generator-v2:0}) —
     *       supports {@code quality} ({@code "standard"} or {@code "premium"}).</li>
     *   <li><strong>Titan Image Generator V1</strong> ({@code amazon.titan-image-generator-v1}) —
     *       legacy; no {@code quality} field.</li>
     * </ul>
     */
    private ObjectNode buildAmazonTextToImagePayload(ImageGenerateRequest request, String modelId) {
        BedrockProperties.Image cfg = properties.getBedrock().getImage();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("taskType", "TEXT_IMAGE");

        ObjectNode params = root.putObject("textToImageParams");
        params.put("text", request.getPrompt());
        if (hasText(request.getNegativePrompt())) {
            params.put("negativeText", request.getNegativePrompt());
        }

        putImageGenerationConfig(root, request.getWidth(), request.getHeight(),
                request.getNumberOfImages(), request.getCfgScale(),
                request.getSeed(), request.getQuality(), cfg, modelId);

        return root;
    }

    /**
     * Builds the Amazon IMAGE_VARIATION payload.
     *
     * <p>Supported by Titan Image Generator V1/V2 and Nova Canvas.
     * Nova Canvas omits the {@code quality} field (it is Titan V2-only).
     */
    private ObjectNode buildImageVariationPayload(ImageVariationRequest request,
                                                   String modelId) {
        BedrockProperties.Image cfg = properties.getBedrock().getImage();

        ObjectNode root   = objectMapper.createObjectNode();
        root.put("taskType", "IMAGE_VARIATION");

        ObjectNode params = root.putObject("imageVariationParams");
        ArrayNode  imgs   = params.putArray("images");
        imgs.add(request.getInputImageBase64());
        if (hasText(request.getPrompt())) {
            params.put("text", request.getPrompt());
        }
        if (hasText(request.getNegativePrompt())) {
            params.put("negativeText", request.getNegativePrompt());
        }
        params.put("similarityStrength",
                request.getSimilarityStrength() != null ? request.getSimilarityStrength() : 0.7);

        // Output dimensions default to the source image; provide config defaults
        putImageGenerationConfig(root, null, null,
                request.getNumberOfImages(), request.getCfgScale(),
                request.getSeed(), request.getQuality(), cfg, modelId);

        return root;
    }

    /**
     * Builds the Stability AI SDXL text-to-image payload.
     */
    private ObjectNode buildStabilityTextPayload(ImageGenerateRequest request) {
        BedrockProperties.Image cfg = properties.getBedrock().getImage();

        ObjectNode root = objectMapper.createObjectNode();

        ArrayNode prompts = root.putArray("text_prompts");
        ObjectNode mainPrompt = prompts.addObject();
        mainPrompt.put("text",   request.getPrompt());
        mainPrompt.put("weight", 1.0);

        if (hasText(request.getNegativePrompt())) {
            ObjectNode neg = prompts.addObject();
            neg.put("text",   request.getNegativePrompt());
            neg.put("weight", -1.0);
        }

        root.put("cfg_scale", request.getCfgScale() != null
                ? request.getCfgScale() : cfg.getDefaultCfgScale());
        root.put("steps",  30);
        root.put("seed",   request.getSeed() != null ? request.getSeed() : 0);
        root.put("width",  request.getWidth()  != null ? request.getWidth()  : cfg.getDefaultWidth());
        root.put("height", request.getHeight() != null ? request.getHeight() : cfg.getDefaultHeight());
        root.put("samples", request.getNumberOfImages() != null
                ? request.getNumberOfImages() : cfg.getDefaultNumberOfImages());

        return root;
    }

    /**
     * Writes the {@code imageGenerationConfig} block into an Amazon image model payload.
     *
     * <p>The {@code quality} field is written only for Titan Image Generator V2 models.
     * Nova Canvas does <em>not</em> accept this field and will return an error if it is present.
     *
     * @param modelId used to determine whether to include {@code quality}
     */
    private void putImageGenerationConfig(ObjectNode root,
                                          Integer width, Integer height,
                                          Integer numberOfImages, Double cfgScale,
                                          Long seed, String quality,
                                          BedrockProperties.Image cfg,
                                          String modelId) {
        ObjectNode config = root.putObject("imageGenerationConfig");
        config.put("numberOfImages", numberOfImages != null ? numberOfImages : cfg.getDefaultNumberOfImages());
        config.put("width",          width          != null ? width          : cfg.getDefaultWidth());
        config.put("height",         height         != null ? height         : cfg.getDefaultHeight());
        config.put("cfgScale",       cfgScale       != null ? cfgScale       : cfg.getDefaultCfgScale());
        if (seed != null && seed != 0) {
            config.put("seed", seed);
        }
        // quality is a Titan V2-only field — Nova Canvas and Titan V1 do not support it
        if (isTitanV2Model(modelId)) {
            if (hasText(quality)) {
                config.put("quality", quality);
            } else if (hasText(cfg.getDefaultQuality())) {
                config.put("quality", cfg.getDefaultQuality());
            }
        }
    }

    // ── Bedrock invocation ────────────────────────────────────────────────────

    private InvokeModelResponse invokeModel(String modelId, ObjectNode payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            log.trace("InvokeModel payload for {}: {}", modelId, body);

            InvokeModelRequest invokeRequest = InvokeModelRequest.builder()
                    .modelId(modelId)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(body))
                    .build();

            return bedrockClient.invokeModel(invokeRequest);
        } catch (BedrockException ex) {
            throw ex;
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (msg.contains("Legacy") || msg.contains("legacy")
                    || (msg.contains("Access denied") && msg.contains("30 days"))) {
                throw new BedrockException(
                        "Image model '" + modelId + "' is inactive or legacy in your AWS account. "
                        + "Fix: AWS Console → Bedrock → Model access → Manage model access → "
                        + "re-enable '" + modelId + "' (or choose a different model and pass it as 'modelId' in the request). "
                        + "AWS requires models to be actively enabled; access may lapse after 30 days of inactivity.",
                        ex);
            }
            throw new BedrockException("InvokeModel (image generation) failed: " + msg, ex);
        }
    }

    // ── Response parsers ──────────────────────────────────────────────────────

    private List<String> extractImages(InvokeModelResponse response, String modelId) {
        return isStabilityModel(modelId)
                ? extractStabilityImages(response)
                : extractTitanImages(response);
    }

    /** Parses the Titan Image Generator response ({@code images} array). */
    private List<String> extractTitanImages(InvokeModelResponse response) {
        try {
            JsonNode root = objectMapper.readTree(response.body().asByteArray());

            JsonNode errorNode = root.get("error");
            if (errorNode != null && !errorNode.isNull() && !errorNode.asText().isBlank()) {
                throw new BedrockException("Titan image generation error: " + errorNode.asText());
            }

            JsonNode imagesNode = root.get("images");
            if (imagesNode == null || !imagesNode.isArray()) {
                throw new BedrockException("Titan returned no 'images' array in response");
            }

            List<String> images = new ArrayList<>(imagesNode.size());
            for (JsonNode img : imagesNode) {
                images.add(img.asText());
            }
            return images;

        } catch (BedrockException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BedrockException("Failed to parse Titan image response: " + ex.getMessage(), ex);
        }
    }

    /** Parses the Stability AI response ({@code artifacts} array). */
    private List<String> extractStabilityImages(InvokeModelResponse response) {
        try {
            JsonNode root = objectMapper.readTree(response.body().asByteArray());

            String result = root.path("result").asText("");
            if (!"success".equalsIgnoreCase(result)) {
                throw new BedrockException("Stability AI returned result: " + result);
            }

            JsonNode artifacts = root.get("artifacts");
            if (artifacts == null || !artifacts.isArray()) {
                throw new BedrockException("Stability AI returned no 'artifacts' array");
            }

            List<String> images = new ArrayList<>(artifacts.size());
            for (JsonNode artifact : artifacts) {
                String finishReason = artifact.path("finishReason").asText("");
                if ("ERROR".equalsIgnoreCase(finishReason)) {
                    throw new BedrockException("Stability AI artifact error: " + finishReason);
                }
                images.add(artifact.path("base64").asText());
            }
            return images;

        } catch (BedrockException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BedrockException("Failed to parse Stability AI response: " + ex.getMessage(), ex);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveModelId(String requestOverride) {
        if (hasText(requestOverride)) return requestOverride;
        return properties.getBedrock().getImage().getModelId();
    }

    private boolean isStabilityModel(String modelId) {
        return modelId != null && modelId.startsWith("stability.");
    }

    /**
     * Returns {@code true} for Titan Image Generator V2, which is the only Amazon
     * image model that accepts the {@code quality} field in its payload.
     */
    private boolean isTitanV2Model(String modelId) {
        return modelId != null && modelId.contains("titan-image-generator-v2");
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
