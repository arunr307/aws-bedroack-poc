package com.example.bedrock.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response from {@code POST /api/images/generate} and {@code POST /api/images/variation}.
 *
 * <p>Each string in {@code images} is a base64-encoded PNG.
 * To save a generated image:
 * <pre>{@code
 * # CLI — decode and save
 * echo '<base64_string>' | base64 -d > output.png
 *
 * # jq — extract first image from response
 * curl ... | jq -r '.images[0]' | base64 -d > output.png
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerateResponse {

    /**
     * Base64-encoded PNG images.
     * Length equals {@code imagesGenerated}.
     * Decode with {@code Base64.getDecoder().decode(images.get(0))}.
     */
    private List<String> images;

    /** Number of images actually returned (may be less than requested on error). */
    private int imagesGenerated;

    /** Bedrock model ID that was used. */
    private String modelId;

    /** Server-side timestamp when the images were generated. */
    private Instant timestamp;
}
