package com.example.bedrock.service;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.AgentRequest;
import com.example.bedrock.model.AgentResponse;
import com.example.bedrock.model.ChatMessage;
import com.example.bedrock.model.ChatResponse;
import com.example.bedrock.model.ToolCallRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent service that drives an agentic loop using the AWS Bedrock Converse API
 * with Tool Use (function calling).
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Send user message to Bedrock along with tool definitions.</li>
 *   <li>If the model responds with {@code StopReason.TOOL_USE}, execute the
 *       requested tools locally and send results back as a {@code USER} message.</li>
 *   <li>Repeat until the model produces a final {@code END_TURN} text reply.</li>
 * </ol>
 *
 * <h2>Built-in tools</h2>
 * <ul>
 *   <li>{@code calculator} — arithmetic and basic math operations</li>
 *   <li>{@code get_current_time} — current UTC/local date and time</li>
 *   <li>{@code string_utils} — text transformation and analysis</li>
 *   <li>{@code unit_converter} — temperature, length, and weight conversions</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    /** Guard against runaway loops (malformed models or circular tool calls). */
    private static final int MAX_ITERATIONS = 10;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a helpful AI assistant with access to tools. "
            + "Use the provided tools whenever they help you answer the user's question accurately. "
            + "After calling a tool, use its result to formulate a clear, concise answer.";

    private static final Set<String> ALL_TOOL_NAMES =
            Set.of("calculator", "get_current_time", "string_utils", "unit_converter");

    private final BedrockRuntimeClient bedrockClient;
    private final BedrockProperties    properties;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Runs the agent loop for the given request and returns the final answer
     * together with a log of all tool calls made.
     *
     * @param request agent request with user message and optional configuration
     * @return final agent response
     * @throws BedrockException if a Bedrock API call fails
     */
    public AgentResponse runAgent(AgentRequest request) {
        String       modelId  = resolveModelId(request);
        List<Message> messages = buildInitialMessages(request);
        List<Tool>    tools    = buildTools(request.getEnabledTools());

        ToolConfiguration toolConfig = ToolConfiguration.builder()
                .tools(tools)
                .build();

        InferenceConfiguration inferenceConfig = InferenceConfiguration.builder()
                .maxTokens(properties.getBedrock().getMaxTokens())
                .temperature((float) properties.getBedrock().getTemperature())
                .build();

        String systemPrompt = (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank())
                ? request.getSystemPrompt()
                : DEFAULT_SYSTEM_PROMPT;

        List<ToolCallRecord> toolCallLog     = new ArrayList<>();
        int                  totalInputTokens  = 0;
        int                  totalOutputTokens = 0;
        int                  iterations        = 0;
        String               finalReply        = null;

        while (iterations < MAX_ITERATIONS) {
            iterations++;
            log.debug("Agent iteration {} — modelId={}, messages={}", iterations, modelId, messages.size());

            ConverseRequest converseRequest = ConverseRequest.builder()
                    .modelId(modelId)
                    .messages(messages)
                    .toolConfig(toolConfig)
                    .inferenceConfig(inferenceConfig)
                    .system(SystemContentBlock.builder().text(systemPrompt).build())
                    .build();

            ConverseResponse converseResponse;
            try {
                converseResponse = bedrockClient.converse(converseRequest);
            } catch (Exception ex) {
                throw new BedrockException("Bedrock Converse API call failed: " + ex.getMessage(), ex);
            }

            totalInputTokens  += safeInputTokens(converseResponse.usage());
            totalOutputTokens += safeOutputTokens(converseResponse.usage());

            StopReason stopReason = converseResponse.stopReason();
            log.debug("Iteration {} stop reason: {}", iterations, stopReason);

            if (stopReason == StopReason.TOOL_USE) {
                Message assistantMessage = converseResponse.output().message();
                messages.add(assistantMessage);

                List<ContentBlock> toolResultBlocks = new ArrayList<>();
                for (ContentBlock block : assistantMessage.content()) {
                    if (block.toolUse() != null) {
                        ToolUseBlock toolUse = block.toolUse();
                        log.debug("Executing tool: {} with input: {}", toolUse.name(), toolUse.input());

                        String result = executeToolSafely(toolUse.name(), toolUse.input());
                        log.debug("Tool {} returned: {}", toolUse.name(), result);

                        toolCallLog.add(ToolCallRecord.builder()
                                .toolName(toolUse.name())
                                .input(toolUse.input())
                                .output(result)
                                .build());

                        toolResultBlocks.add(ContentBlock.fromToolResult(
                                ToolResultBlock.builder()
                                        .toolUseId(toolUse.toolUseId())
                                        .content(ToolResultContentBlock.fromText(result))
                                        .build()));
                    }
                }

                messages.add(Message.builder()
                        .role(ConversationRole.USER)
                        .content(toolResultBlocks)
                        .build());

            } else {
                // END_TURN or any other terminal stop reason
                finalReply = extractTextReply(converseResponse);
                break;
            }
        }

        if (finalReply == null) {
            throw new BedrockException(
                    "Agent exceeded the maximum number of iterations (" + MAX_ITERATIONS + ") without producing a final answer");
        }

        return AgentResponse.builder()
                .reply(finalReply)
                .modelId(modelId)
                .toolCalls(toolCallLog)
                .iterations(iterations)
                .usage(ChatResponse.TokenUsage.builder()
                        .inputTokens(totalInputTokens)
                        .outputTokens(totalOutputTokens)
                        .totalTokens(totalInputTokens + totalOutputTokens)
                        .build())
                .timestamp(Instant.now())
                .build();
    }

    // ── Message builders ──────────────────────────────────────────────────────

    private List<Message> buildInitialMessages(AgentRequest request) {
        List<Message> messages = new ArrayList<>();

        if (request.getConversationHistory() != null) {
            for (ChatMessage cm : request.getConversationHistory()) {
                messages.add(Message.builder()
                        .role(cm.getRole() == ChatMessage.Role.USER
                                ? ConversationRole.USER
                                : ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText(cm.getContent()))
                        .build());
            }
        }

        messages.add(Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(request.getMessage()))
                .build());

        return messages;
    }

    // ── Tool definitions ──────────────────────────────────────────────────────

    private List<Tool> buildTools(List<String> enabledTools) {
        Set<String> enabled = (enabledTools == null || enabledTools.isEmpty())
                ? ALL_TOOL_NAMES
                : Set.copyOf(enabledTools);

        List<Tool> tools = new ArrayList<>();
        if (enabled.contains("calculator"))      tools.add(calculatorTool());
        if (enabled.contains("get_current_time")) tools.add(getCurrentTimeTool());
        if (enabled.contains("string_utils"))    tools.add(stringUtilsTool());
        if (enabled.contains("unit_converter"))  tools.add(unitConverterTool());
        return tools;
    }

    private Tool calculatorTool() {
        Document schema = Document.fromMap(Map.of(
                "type", Document.fromString("object"),
                "properties", Document.fromMap(Map.of(
                        "operation", Document.fromMap(Map.of(
                                "type", Document.fromString("string"),
                                "enum", Document.fromList(List.of(
                                        Document.fromString("add"),
                                        Document.fromString("subtract"),
                                        Document.fromString("multiply"),
                                        Document.fromString("divide"),
                                        Document.fromString("power"),
                                        Document.fromString("sqrt"),
                                        Document.fromString("modulo")
                                )),
                                "description", Document.fromString("The math operation to perform")
                        )),
                        "a", Document.fromMap(Map.of(
                                "type", Document.fromString("number"),
                                "description", Document.fromString("The first operand")
                        )),
                        "b", Document.fromMap(Map.of(
                                "type", Document.fromString("number"),
                                "description", Document.fromString("The second operand (not required for sqrt)")
                        ))
                )),
                "required", Document.fromList(List.of(
                        Document.fromString("operation"),
                        Document.fromString("a")
                ))
        ));

        return Tool.fromToolSpec(ToolSpecification.builder()
                .name("calculator")
                .description("Perform arithmetic and basic mathematical operations: add, subtract, multiply, divide, power, sqrt, modulo.")
                .inputSchema(ToolInputSchema.fromJson(schema))
                .build());
    }

    private Tool getCurrentTimeTool() {
        Document schema = Document.fromMap(Map.of(
                "type", Document.fromString("object"),
                "properties", Document.fromMap(Map.of(
                        "timezone", Document.fromMap(Map.of(
                                "type", Document.fromString("string"),
                                "description", Document.fromString(
                                        "IANA timezone name (e.g. 'America/New_York', 'Europe/London'). Defaults to UTC.")
                        )),
                        "format", Document.fromMap(Map.of(
                                "type", Document.fromString("string"),
                                "enum", Document.fromList(List.of(
                                        Document.fromString("iso8601"),
                                        Document.fromString("date_only"),
                                        Document.fromString("time_only"),
                                        Document.fromString("human_readable")
                                )),
                                "description", Document.fromString("Output format. Defaults to iso8601.")
                        ))
                )),
                "required", Document.fromList(List.of())
        ));

        return Tool.fromToolSpec(ToolSpecification.builder()
                .name("get_current_time")
                .description("Get the current date and time, optionally in a specific timezone and format.")
                .inputSchema(ToolInputSchema.fromJson(schema))
                .build());
    }

    private Tool stringUtilsTool() {
        Document schema = Document.fromMap(Map.of(
                "type", Document.fromString("object"),
                "properties", Document.fromMap(Map.of(
                        "operation", Document.fromMap(Map.of(
                                "type", Document.fromString("string"),
                                "enum", Document.fromList(List.of(
                                        Document.fromString("uppercase"),
                                        Document.fromString("lowercase"),
                                        Document.fromString("reverse"),
                                        Document.fromString("word_count"),
                                        Document.fromString("char_count"),
                                        Document.fromString("trim")
                                )),
                                "description", Document.fromString("The string operation to perform")
                        )),
                        "text", Document.fromMap(Map.of(
                                "type", Document.fromString("string"),
                                "description", Document.fromString("The input text to process")
                        ))
                )),
                "required", Document.fromList(List.of(
                        Document.fromString("operation"),
                        Document.fromString("text")
                ))
        ));

        return Tool.fromToolSpec(ToolSpecification.builder()
                .name("string_utils")
                .description("Perform text transformations: uppercase, lowercase, reverse, count words, count characters, or trim whitespace.")
                .inputSchema(ToolInputSchema.fromJson(schema))
                .build());
    }

    private Tool unitConverterTool() {
        Document schema = Document.fromMap(Map.of(
                "type", Document.fromString("object"),
                "properties", Document.fromMap(Map.of(
                        "value", Document.fromMap(Map.of(
                                "type", Document.fromString("number"),
                                "description", Document.fromString("The numeric value to convert")
                        )),
                        "from_unit", Document.fromMap(Map.of(
                                "type", Document.fromString("string"),
                                "description", Document.fromString(
                                        "Source unit. Temperature: celsius, fahrenheit, kelvin. "
                                        + "Length: meters, feet, inches, kilometers, miles. "
                                        + "Weight: kilograms, pounds, grams, ounces.")
                        )),
                        "to_unit", Document.fromMap(Map.of(
                                "type", Document.fromString("string"),
                                "description", Document.fromString("Target unit (same categories as from_unit)")
                        ))
                )),
                "required", Document.fromList(List.of(
                        Document.fromString("value"),
                        Document.fromString("from_unit"),
                        Document.fromString("to_unit")
                ))
        ));

        return Tool.fromToolSpec(ToolSpecification.builder()
                .name("unit_converter")
                .description("Convert between units of temperature (celsius/fahrenheit/kelvin), length (meters/feet/inches/kilometers/miles), and weight (kilograms/pounds/grams/ounces).")
                .inputSchema(ToolInputSchema.fromJson(schema))
                .build());
    }

    // ── Tool execution ────────────────────────────────────────────────────────

    private String executeToolSafely(String toolName, Document input) {
        try {
            return executeTool(toolName, input);
        } catch (Exception ex) {
            log.warn("Tool '{}' threw an exception: {}", toolName, ex.getMessage());
            return "Error executing tool '" + toolName + "': " + ex.getMessage();
        }
    }

    public String executeTool(String toolName, Document input) {
        Map<String, Document> params = input.asMap();
        return switch (toolName) {
            case "calculator"      -> executeCalculator(params);
            case "get_current_time" -> executeGetCurrentTime(params);
            case "string_utils"    -> executeStringUtils(params);
            case "unit_converter"  -> executeUnitConverter(params);
            default -> "Unknown tool: " + toolName;
        };
    }

    private String executeCalculator(Map<String, Document> params) {
        String operation = params.get("operation").asString();
        double a = params.get("a").asNumber().doubleValue();
        double b = params.containsKey("b") ? params.get("b").asNumber().doubleValue() : 0.0;

        double result = switch (operation) {
            case "add"      -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide"   -> {
                if (b == 0) throw new ArithmeticException("Division by zero");
                yield a / b;
            }
            case "power"    -> Math.pow(a, b);
            case "sqrt"     -> {
                if (a < 0) throw new ArithmeticException("Square root of negative number");
                yield Math.sqrt(a);
            }
            case "modulo"   -> {
                if (b == 0) throw new ArithmeticException("Modulo by zero");
                yield a % b;
            }
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };

        // Format cleanly: strip trailing .0 for whole numbers
        if (result == Math.floor(result) && !Double.isInfinite(result)) {
            return String.valueOf((long) result);
        }
        return String.valueOf(result);
    }

    private String executeGetCurrentTime(Map<String, Document> params) {
        String timezone = params.containsKey("timezone")
                ? params.get("timezone").asString()
                : "UTC";
        String format = params.containsKey("format")
                ? params.get("format").asString()
                : "iso8601";

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone);
        } catch (Exception ex) {
            zoneId = ZoneId.of("UTC");
            timezone = "UTC (invalid timezone '" + timezone + "' — defaulted)";
        }

        ZonedDateTime now = ZonedDateTime.now(zoneId);

        return switch (format) {
            case "date_only"      -> now.format(DateTimeFormatter.ISO_LOCAL_DATE);
            case "time_only"      -> now.format(DateTimeFormatter.ISO_LOCAL_TIME);
            case "human_readable" -> now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm:ss a z"));
            default               -> now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        };
    }

    private String executeStringUtils(Map<String, Document> params) {
        String operation = params.get("operation").asString();
        String text      = params.get("text").asString();

        return switch (operation) {
            case "uppercase"  -> text.toUpperCase();
            case "lowercase"  -> text.toLowerCase();
            case "reverse"    -> new StringBuilder(text).reverse().toString();
            case "word_count" -> String.valueOf(text.isBlank() ? 0 : text.trim().split("\\s+").length);
            case "char_count" -> String.valueOf(text.length());
            case "trim"       -> text.trim();
            default -> throw new IllegalArgumentException("Unknown string operation: " + operation);
        };
    }

    private String executeUnitConverter(Map<String, Document> params) {
        double value    = params.get("value").asNumber().doubleValue();
        String fromUnit = params.get("from_unit").asString().toLowerCase();
        String toUnit   = params.get("to_unit").asString().toLowerCase();

        if (fromUnit.equals(toUnit)) {
            return formatConversionResult(value, value, fromUnit, toUnit);
        }

        double result = convertUnit(value, fromUnit, toUnit);
        return formatConversionResult(value, result, fromUnit, toUnit);
    }

    private double convertUnit(double value, String from, String to) {
        // ── Temperature ───────────────────────────────────────────────────────
        if (isTemperature(from) && isTemperature(to)) {
            double celsius = switch (from) {
                case "celsius"    -> value;
                case "fahrenheit" -> (value - 32) * 5.0 / 9.0;
                case "kelvin"     -> value - 273.15;
                default -> throw new IllegalArgumentException("Unknown temperature unit: " + from);
            };
            return switch (to) {
                case "celsius"    -> celsius;
                case "fahrenheit" -> celsius * 9.0 / 5.0 + 32;
                case "kelvin"     -> celsius + 273.15;
                default -> throw new IllegalArgumentException("Unknown temperature unit: " + to);
            };
        }

        // ── Length (normalise to meters) ──────────────────────────────────────
        if (isLength(from) && isLength(to)) {
            double meters = switch (from) {
                case "meters"     -> value;
                case "feet"       -> value * 0.3048;
                case "inches"     -> value * 0.0254;
                case "kilometers" -> value * 1000.0;
                case "miles"      -> value * 1609.344;
                default -> throw new IllegalArgumentException("Unknown length unit: " + from);
            };
            return switch (to) {
                case "meters"     -> meters;
                case "feet"       -> meters / 0.3048;
                case "inches"     -> meters / 0.0254;
                case "kilometers" -> meters / 1000.0;
                case "miles"      -> meters / 1609.344;
                default -> throw new IllegalArgumentException("Unknown length unit: " + to);
            };
        }

        // ── Weight (normalise to kilograms) ───────────────────────────────────
        if (isWeight(from) && isWeight(to)) {
            double kg = switch (from) {
                case "kilograms" -> value;
                case "pounds"    -> value * 0.45359237;
                case "grams"     -> value / 1000.0;
                case "ounces"    -> value * 0.028349523;
                default -> throw new IllegalArgumentException("Unknown weight unit: " + from);
            };
            return switch (to) {
                case "kilograms" -> kg;
                case "pounds"    -> kg / 0.45359237;
                case "grams"     -> kg * 1000.0;
                case "ounces"    -> kg / 0.028349523;
                default -> throw new IllegalArgumentException("Unknown weight unit: " + to);
            };
        }

        throw new IllegalArgumentException(
                "Cannot convert from '" + from + "' to '" + to + "' — units must be in the same category");
    }

    private boolean isTemperature(String unit) {
        return Set.of("celsius", "fahrenheit", "kelvin").contains(unit);
    }

    private boolean isLength(String unit) {
        return Set.of("meters", "feet", "inches", "kilometers", "miles").contains(unit);
    }

    private boolean isWeight(String unit) {
        return Set.of("kilograms", "pounds", "grams", "ounces").contains(unit);
    }

    private String formatConversionResult(double from, double to, String fromUnit, String toUnit) {
        return String.format("%.6f %s = %.6f %s", from, fromUnit, to, toUnit);
    }

    // ── Response helpers ──────────────────────────────────────────────────────

    private String extractTextReply(ConverseResponse response) {
        return response.output()
                .message()
                .content()
                .stream()
                .filter(b -> b.text() != null)
                .map(ContentBlock::text)
                .findFirst()
                .orElseThrow(() -> new BedrockException("Model returned an empty response"));
    }

    private String resolveModelId(AgentRequest request) {
        if (request.getModelId() != null && !request.getModelId().isBlank()) {
            return request.getModelId();
        }
        return properties.getBedrock().getModelId();
    }

    private int safeInputTokens(TokenUsage usage) {
        return usage != null ? usage.inputTokens() : 0;
    }

    private int safeOutputTokens(TokenUsage usage) {
        return usage != null ? usage.outputTokens() : 0;
    }
}
