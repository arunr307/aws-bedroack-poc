package com.example.bedrock.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration properties bound from {@code application.yml}.
 *
 * <p>All values under the {@code aws} prefix are mapped here so that
 * other beans can be injected with this class rather than relying on
 * raw {@code @Value} annotations.
 *
 * <pre>{@code
 * aws:
 *   region: us-east-1
 *   credentials:
 *     access-key: ...
 *     secret-key: ...
 *   bedrock:
 *     model-id: anthropic.claude-3-5-sonnet-20241022-v2:0
 *     max-tokens: 2048
 *     temperature: 0.7
 *     max-conversation-turns: 10
 * }</pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "aws")
public class BedrockProperties {

    /** AWS region where Bedrock is enabled (e.g. {@code us-east-1}). */
    private String region;

    /** Static credential block. */
    private Credentials credentials = new Credentials();

    /** Bedrock-specific settings. */
    private Bedrock bedrock = new Bedrock();

    @Data
    public static class Credentials {
        private String accessKey;
        private String secretKey;
    }

    @Data
    public static class Bedrock {
        /** Bedrock model ID (e.g. {@code anthropic.claude-3-5-sonnet-20241022-v2:0}). */
        private String modelId;

        /** Maximum number of tokens the model may generate per response. */
        private int maxTokens = 2048;

        /** Sampling temperature (0.0–1.0). */
        private double temperature = 0.7;

        /**
         * How many conversation turns (user + assistant pairs) to keep in memory
         * when using the multi-turn endpoint. Set to 0 for fully stateless mode.
         */
        private int maxConversationTurns = 10;
    }
}
