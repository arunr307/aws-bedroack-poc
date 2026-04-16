package com.example.bedrock.exception;

/**
 * Runtime exception wrapping AWS Bedrock API errors so they can be handled
 * uniformly by {@link GlobalExceptionHandler}.
 */
public class BedrockException extends RuntimeException {

    public BedrockException(String message) {
        super(message);
    }

    public BedrockException(String message, Throwable cause) {
        super(message, cause);
    }
}
