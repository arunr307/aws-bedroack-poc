package com.example.bedrock.controller;

import com.example.bedrock.model.ImageGenerateRequest;
import com.example.bedrock.model.ImageGenerateResponse;
import com.example.bedrock.model.ImageVariationRequest;
import com.example.bedrock.service.ImageGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller exposing the Image Generation API.
 *
 * <h2>Base path</h2>
 * {@code /api/images}
 *
 * <h2>Endpoints</h2>
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/images/generate</td><td>Text-to-image generation</td></tr>
 *   <tr><td>POST</td><td>/api/images/variation</td><td>Generate variations of an existing image</td></tr>
 *   <tr><td>GET</td><td>/api/images/models</td><td>List supported image models</td></tr>
 *   <tr><td>GET</td><td>/api/images/health</td><td>Service health check</td></tr>
 * </table>
 *
 * <h2>Example — text-to-image</h2>
 * <pre>{@code
 * curl -X POST http://localhost:8080/api/images/generate \
 *      -H "Content-Type: application/json" \
 *      -d '{ "prompt": "A photorealistic mountain sunset, golden hour, 8K" }' \
 *   | jq -r '.images[0]' | base64 -d > output.png
 * }</pre>
 *
 * <h2>Response images</h2>
 * <p>All images are returned as base64-encoded PNG strings in the {@code images} array.
 * Decode with {@code base64 -d} (CLI) or {@code Base64.getDecoder().decode()} (Java).
 */
@Slf4j
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageGenerationController {

    private final ImageGenerationService imageGenerationService;

    /**
     * Generate one or more images from a text prompt.
     *
     * @param request generation parameters — prompt required, all others optional
     * @return base64-encoded PNG image(s)
     */
    @PostMapping("/generate")
    public ResponseEntity<ImageGenerateResponse> generate(
            @Valid @RequestBody ImageGenerateRequest request) {

        log.info("Image generate request — prompt='{}', size={}x{}, n={}",
                request.getPrompt(),
                request.getWidth(),
                request.getHeight(),
                request.getNumberOfImages());

        ImageGenerateResponse response = imageGenerationService.generateImage(request);

        log.info("Image generate complete — modelId={}, imagesGenerated={}",
                response.getModelId(), response.getImagesGenerated());

        return ResponseEntity.ok(response);
    }

    /**
     * Generate variations of an existing image.
     *
     * <p>The input image must be supplied as a base64-encoded string (PNG or JPEG).
     * Supported by Titan Image Generator models only.
     *
     * @param request variation parameters — inputImageBase64 required
     * @return base64-encoded PNG variation(s)
     */
    @PostMapping("/variation")
    public ResponseEntity<ImageGenerateResponse> variation(
            @Valid @RequestBody ImageVariationRequest request) {

        log.info("Image variation request — similarityStrength={}, n={}",
                request.getSimilarityStrength(), request.getNumberOfImages());

        ImageGenerateResponse response = imageGenerationService.generateVariation(request);

        log.info("Image variation complete — modelId={}, imagesGenerated={}",
                response.getModelId(), response.getImagesGenerated());

        return ResponseEntity.ok(response);
    }

    /**
     * List the supported image generation models and their capabilities.
     *
     * @return map of model ID → description
     */
    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> listModels() {
        return ResponseEntity.ok(Map.of(
                "default", "amazon.titan-image-generator-v2:0",
                "models", Map.of(
                        "amazon.titan-image-generator-v2:0", Map.of(
                                "provider",     "Amazon",
                                "tasks",        new String[]{"TEXT_IMAGE", "IMAGE_VARIATION"},
                                "maxImages",    5,
                                "quality",      new String[]{"standard", "premium"},
                                "dimensions",   "256–1408 px (multiples of 64)",
                                "formRequired", false
                        ),
                        "amazon.titan-image-generator-v1", Map.of(
                                "provider",     "Amazon",
                                "tasks",        new String[]{"TEXT_IMAGE", "IMAGE_VARIATION"},
                                "maxImages",    5,
                                "dimensions",   "512x512, 768x768, 512x768, 768x512, 1024x1024",
                                "formRequired", false
                        ),
                        "stability.stable-diffusion-xl-v1", Map.of(
                                "provider",     "Stability AI",
                                "tasks",        new String[]{"TEXT_IMAGE"},
                                "maxImages",    1,
                                "dimensions",   "1024x1024 (recommended)",
                                "formRequired", true,
                                "note",         "Requires Stability AI model access form in AWS Console"
                        )
                )
        ));
    }

    /**
     * Health-check endpoint for the image generation service.
     *
     * @return {@code 200 OK} with a status message
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Image generation service is running");
    }
}
