package com.example.bedrock.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.concurrent.Executor;

/**
 * Spring configuration that produces a {@link BedrockRuntimeClient} bean.
 *
 * <h2>Credential resolution strategy</h2>
 * <ol>
 *   <li>If {@code aws.credentials.access-key} is set in {@code application.yml},
 *       static credentials are used (good for local dev / quick testing).</li>
 *   <li>Otherwise the SDK's {@link DefaultCredentialsProvider} chain is used,
 *       which checks environment variables, AWS profiles, EC2 instance metadata,
 *       and IAM roles — the recommended approach for production.</li>
 * </ol>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class BedrockConfig {

    private final BedrockProperties properties;

    /**
     * Creates and returns a configured {@link BedrockRuntimeClient}.
     *
     * <p>The client is a heavyweight object and should be a singleton
     * (Spring's default for {@code @Bean} methods).
     */
    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient() {
        AwsCredentialsProvider credentialsProvider = resolveCredentialsProvider();
        Region region = Region.of(properties.getRegion());

        log.info("Initialising BedrockRuntimeClient — region={}, modelId={}",
                region, properties.getBedrock().getModelId());

        return BedrockRuntimeClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();
    }

    /**
     * Async Bedrock client used exclusively by {@link com.example.bedrock.service.StreamingChatService}.
     *
     * <p>The async client is required for {@code converseStream()} — the sync client does not
     * expose a streaming variant. We call {@code .join()} on the returned
     * {@link java.util.concurrent.CompletableFuture} inside the dedicated
     * {@code streamingExecutor} thread pool, so the async completion is transparent to callers.
     */
    @Bean
    public BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient() {
        AwsCredentialsProvider credentialsProvider = resolveCredentialsProvider();
        Region region = Region.of(properties.getRegion());

        log.info("Initialising BedrockRuntimeAsyncClient — region={}", region);

        return BedrockRuntimeAsyncClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();
    }

    /**
     * Thread pool used by the streaming chat endpoint to run {@code converseStream()}
     * calls off the Tomcat request thread.
     *
     * <p>{@code converseStream()} is blocking — it does not return until the model
     * finishes generating. Running it on a Tomcat thread would exhaust the thread pool
     * under concurrent load. This dedicated executor keeps Tomcat threads free.
     *
     * <p>Sizing: 10 core threads handles 10 simultaneous streaming requests; the queue
     * absorbs short bursts beyond that. Adjust via {@code application.yml} if needed.
     */
    @Bean(name = "streamingExecutor")
    public Executor streamingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("bedrock-stream-");
        executor.initialize();
        return executor;
    }

    /**
     * Returns a {@link StaticCredentialsProvider} when explicit keys are
     * configured; falls back to the SDK's default provider chain otherwise.
     */
    private AwsCredentialsProvider resolveCredentialsProvider() {
        String accessKey = properties.getCredentials().getAccessKey();
        String secretKey = properties.getCredentials().getSecretKey();

        boolean isStaticCredentials = accessKey != null
                && !accessKey.isBlank()
                && !accessKey.equals("YOUR_AWS_ACCESS_KEY_ID");

        if (isStaticCredentials) {
            log.info("Using static AWS credentials from application.yml");
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));
        }

        log.info("Using DefaultCredentialsProvider chain (env vars / profile / instance role)");
        return DefaultCredentialsProvider.create();
    }
}
