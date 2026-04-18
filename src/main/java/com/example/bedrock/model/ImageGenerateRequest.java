package com.example.bedrock.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/images/generate}.
 *
 * <h2>Minimal request</h2>
 * <pre>{@code
 * { "prompt": "A photorealistic sunset over misty mountains" }
 * }</pre>
 *
 * <h2>Full request</h2>
 * <pre>{@code
 * {
 *   "prompt":         "A photorealistic sunset over misty mountains, golden hour",
 *   "negativePrompt": "blurry, low quality, cartoon, watermark",
 *   "width":          1024,
 *   "height":         1024,
 *   "numberOfImages": 2,
 *   "cfgScale":       8.0,
 *   "seed":           42,
 *   "quality":        "premium",
 *   "modelId":        "amazon.titan-image-generator-v2:0"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerateRequest {

    /** Text description of the image to generate. */
    @NotBlank(message = "prompt must not be blank")
    @Size(max = 512, message = "prompt must not exceed 512 characters")
    private String prompt;

    /** Concepts to exclude from the generated image. */
    @Size(max = 512, message = "negativePrompt must not exceed 512 characters")
    private String negativePrompt;

    /** Output image width in pixels. Titan V2: 256–1408, multiples of 64. */
    @Min(value = 64,   message = "width must be at least 64")
    @Max(value = 2048, message = "width must not exceed 2048")
    private Integer width;

    /** Output image height in pixels. Titan V2: 256–1408, multiples of 64. */
    @Min(value = 64,   message = "height must be at least 64")
    @Max(value = 2048, message = "height must not exceed 2048")
    private Integer height;

    /** Number of images to generate in one call (1–5). */
    @Min(value = 1, message = "numberOfImages must be at least 1")
    @Max(value = 5, message = "numberOfImages must not exceed 5")
    private Integer numberOfImages;

    /**
     * Classifier-Free Guidance scale.
     * Controls how strictly the model follows the prompt.
     * Range 1.1–10.0 for Titan; 1–35 for Stability AI.
     */
    private Double cfgScale;

    /**
     * Random seed for reproducible outputs.
     * Use the same seed + prompt to get the same image again.
     * {@code null} or {@code 0} → random seed.
     */
    private Long seed;

    /**
     * Output quality preset (Titan Image Generator V2 only).
     * {@code "standard"} — faster generation.
     * {@code "premium"} — higher detail and fidelity.
     */
    private String quality;

    /** Optional Bedrock model ID override. Falls back to {@code aws.bedrock.image.model-id}. */
    private String modelId;
}
