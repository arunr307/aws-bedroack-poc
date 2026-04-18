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
        /** Bedrock model ID (e.g. {@code amazon.nova-lite-v1:0}). */
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

        /** Embedding-specific settings. */
        private Embedding embedding = new Embedding();

        /** Managed Knowledge Base settings. */
        private KnowledgeBase knowledgeBase = new KnowledgeBase();

        /** Image generation settings. */
        private Image image = new Image();

        /** Prompt Flow settings. */
        private Flow flow = new Flow();
    }

    @Data
    public static class Embedding {
        /**
         * Bedrock model used to generate embeddings.
         * Titan Embed Text V2 supports variable dimensions and normalisation.
         */
        private String modelId = "amazon.titan-embed-text-v2:0";

        /**
         * Vector dimensionality produced by the embedding model.
         * Titan Embed Text V2 supports: {@code 256}, {@code 512}, {@code 1024}.
         * Larger dimensions capture more nuance but cost more to store and compare.
         */
        private int dimensions = 1024;

        /**
         * Whether to normalise the output vector to unit length (L2 norm = 1).
         * Recommended {@code true} — enables direct dot-product as cosine similarity.
         */
        private boolean normalize = true;
    }

    @Data
    public static class KnowledgeBase {
        /**
         * The Knowledge Base ID from AWS Console → Bedrock → Knowledge Bases.
         * Set via the {@code KB_ID} environment variable for local development.
         * When blank, Knowledge Base endpoints return a descriptive error.
         */
        private String id = "";

        /**
         * Bedrock model used for answer generation in the {@code RetrieveAndGenerate} call.
         * Must be enabled in your account's Bedrock model access.
         */
        private String modelId = "amazon.nova-lite-v1:0";

        /**
         * Default number of chunks to retrieve per query (1–100).
         * Can be overridden per request via {@code topK}.
         */
        private int defaultTopK = 5;
    }

    @Data
    public static class Image {
        /**
         * Default Bedrock image generation model.
         * Amazon Nova Canvas is the current-generation Amazon image model and
         * requires no special use-case form.
         * Stability AI models ({@code stability.*}) require separate model access.
         */
        private String modelId = "amazon.nova-canvas-v1:0";

        /** Default output image width in pixels. */
        private int defaultWidth = 1024;

        /** Default output image height in pixels. */
        private int defaultHeight = 1024;

        /** Default number of images to generate per request (1–5). */
        private int defaultNumberOfImages = 1;

        /**
         * Classifier-Free Guidance scale — controls how closely the model follows
         * the prompt. Range 1.1–10.0 for Titan; 1–35 for Stability AI.
         * Higher = more prompt-faithful; lower = more creative.
         */
        private double defaultCfgScale = 8.0;

        /**
         * Default output quality for Titan Image Generator V2.
         * {@code "standard"} (faster) or {@code "premium"} (higher detail).
         */
        private String defaultQuality = "standard";
    }

    @Data
    public static class Flow {
        /**
         * Default Flow ID from AWS Console → Bedrock → Flows.
         * Set via the {@code FLOW_ID} environment variable for local development.
         * When blank, a Flow ID must be supplied in each request.
         */
        private String defaultFlowId = "";

        /**
         * Default Flow Alias ID (e.g. {@code TSTALIASID} for the draft alias).
         * Set via the {@code FLOW_ALIAS_ID} environment variable for local development.
         * When blank, an alias ID must be supplied in each request.
         */
        private String defaultAliasId = "";
    }
}
