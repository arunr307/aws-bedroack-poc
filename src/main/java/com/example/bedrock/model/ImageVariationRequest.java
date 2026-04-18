package com.example.bedrock.model;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/images/variation}.
 *
 * <p>Generates one or more variations of an existing image.
 * The input image must be base64-encoded (PNG or JPEG).
 *
 * <h2>Example</h2>
 * <pre>{@code
 * {
 *   "inputImageBase64":  "<base64 string>",
 *   "prompt":            "same scene but at night",
 *   "similarityStrength": 0.7,
 *   "numberOfImages":    1
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageVariationRequest {

    /** Base64-encoded source image (PNG or JPEG, no data-URI prefix). */
    @NotBlank(message = "inputImageBase64 must not be blank")
    private String inputImageBase64;

    /** Optional text prompt to guide how the variation differs from the original. */
    @Size(max = 512, message = "prompt must not exceed 512 characters")
    private String prompt;

    /** Concepts to exclude from the generated variation. */
    @Size(max = 512, message = "negativePrompt must not exceed 512 characters")
    private String negativePrompt;

    /**
     * How closely the variation should resemble the input image.
     * Range 0.2–1.0: higher = more similar to input, lower = more creative freedom.
     * Defaults to 0.7 when not specified.
     */
    @DecimalMin(value = "0.2", message = "similarityStrength must be at least 0.2")
    @DecimalMax(value = "1.0", message = "similarityStrength must not exceed 1.0")
    private Double similarityStrength;

    /** Number of variations to generate (1–5). */
    @Min(value = 1, message = "numberOfImages must be at least 1")
    @Max(value = 5, message = "numberOfImages must not exceed 5")
    private Integer numberOfImages;

    /** Classifier-Free Guidance scale (1.1–10.0 for Titan). */
    private Double cfgScale;

    /** Seed for reproducibility ({@code null} / {@code 0} = random). */
    private Long seed;

    /** Quality preset: {@code "standard"} or {@code "premium"} (Titan V2 only). */
    private String quality;

    /** Optional Bedrock model ID override. */
    private String modelId;
}
