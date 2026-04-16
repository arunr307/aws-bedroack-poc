package com.example.bedrock.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralised error handling for all REST controllers.
 *
 * <p>Returns RFC 7807 {@link ProblemDetail} responses so callers receive
 * machine-readable, structured error payloads.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles validation errors on {@code @Valid} request bodies.
     *
     * <p>Example response:
     * <pre>{@code
     * {
     *   "type":   "about:blank",
     *   "title":  "Bad Request",
     *   "status": 400,
     *   "detail": "Validation failed",
     *   "errors": { "message": "must not be blank" }
     * }
     * }</pre>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage,
                        (a, b) -> a));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setType(URI.create("about:blank"));
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    /**
     * Handles AWS Bedrock API errors (model not available, throttling, etc.).
     */
    @ExceptionHandler(BedrockException.class)
    public ProblemDetail handleBedrock(BedrockException ex) {
        log.error("Bedrock API error: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setTitle("Bedrock API Error");
        return problem;
    }

    /**
     * Catch-all for unexpected exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
}
