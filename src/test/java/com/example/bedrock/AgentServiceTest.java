package com.example.bedrock;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.AgentRequest;
import com.example.bedrock.model.AgentResponse;
import com.example.bedrock.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentService}.
 *
 * <p>The Bedrock client is mocked so no real AWS calls are made.
 * Tool execution logic is tested directly via package-private {@code executeTool}.
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private BedrockRuntimeClient bedrockClient;

    private AgentService agentService;

    @BeforeEach
    void setUp() {
        BedrockProperties props = new BedrockProperties();
        props.setRegion("us-east-1");

        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        bedrock.setModelId("amazon.nova-lite-v1:0");
        bedrock.setMaxTokens(2048);
        bedrock.setTemperature(0.7);
        props.setBedrock(bedrock);

        agentService = new AgentService(bedrockClient, props);
    }

    // ── runAgent: no tool use ─────────────────────────────────────────────────

    @Test
    void runAgent_noToolUse_returnsFinalReply() {
        stubEndTurnResponse("The answer is 42.");

        AgentRequest request = AgentRequest.builder()
                .message("What is the meaning of life?")
                .build();

        AgentResponse response = agentService.runAgent(request);

        assertThat(response.getReply()).isEqualTo("The answer is 42.");
        assertThat(response.getIterations()).isEqualTo(1);
        assertThat(response.getToolCalls()).isEmpty();
        assertThat(response.getModelId()).isEqualTo("amazon.nova-lite-v1:0");
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void runAgent_modelIdOverride_usesOverrideModel() {
        stubEndTurnResponse("Hello!");

        AgentRequest request = AgentRequest.builder()
                .message("Hi")
                .modelId("amazon.nova-pro-v1:0")
                .build();

        AgentResponse response = agentService.runAgent(request);

        assertThat(response.getModelId()).isEqualTo("amazon.nova-pro-v1:0");
    }

    // ── runAgent: with tool calls ─────────────────────────────────────────────

    @Test
    void runAgent_singleToolCall_executesToolAndReturnsAnswer() {
        // First call: model requests the calculator tool
        ConverseResponse toolUseResponse = buildToolUseResponse(
                "tool-1",
                "calculator",
                Document.fromMap(Map.of(
                        "operation", Document.fromString("multiply"),
                        "a", Document.fromNumber(1234),
                        "b", Document.fromNumber(5678)
                ))
        );

        // Second call: model produces final answer
        ConverseResponse finalResponse = buildEndTurnResponse("1234 × 5678 = 7,006,652.");

        when(bedrockClient.converse(any(ConverseRequest.class)))
                .thenReturn(toolUseResponse)
                .thenReturn(finalResponse);

        AgentRequest request = AgentRequest.builder()
                .message("What is 1234 multiplied by 5678?")
                .build();

        AgentResponse response = agentService.runAgent(request);

        assertThat(response.getReply()).isEqualTo("1234 × 5678 = 7,006,652.");
        assertThat(response.getIterations()).isEqualTo(2);
        assertThat(response.getToolCalls()).hasSize(1);
        assertThat(response.getToolCalls().get(0).getToolName()).isEqualTo("calculator");
        assertThat(response.getToolCalls().get(0).getOutput()).isEqualTo("7006652");
    }

    @Test
    void runAgent_multipleToolCalls_logsAllInvocations() {
        // First iteration: two tool use blocks in one response
        ToolUseBlock toolUse1 = ToolUseBlock.builder()
                .toolUseId("id-1")
                .name("calculator")
                .input(Document.fromMap(Map.of(
                        "operation", Document.fromString("add"),
                        "a", Document.fromNumber(10),
                        "b", Document.fromNumber(20)
                )))
                .build();

        ToolUseBlock toolUse2 = ToolUseBlock.builder()
                .toolUseId("id-2")
                .name("string_utils")
                .input(Document.fromMap(Map.of(
                        "operation", Document.fromString("uppercase"),
                        "text", Document.fromString("hello world")
                )))
                .build();

        Message assistantWithTwoTools = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(
                        ContentBlock.fromToolUse(toolUse1),
                        ContentBlock.fromToolUse(toolUse2)
                )
                .build();

        ConverseResponse toolUseResponse = ConverseResponse.builder()
                .output(ConverseOutput.builder().message(assistantWithTwoTools).build())
                .stopReason(StopReason.TOOL_USE)
                .usage(buildUsage(20, 10))
                .build();

        ConverseResponse finalResponse = buildEndTurnResponse("Done!");

        when(bedrockClient.converse(any(ConverseRequest.class)))
                .thenReturn(toolUseResponse)
                .thenReturn(finalResponse);

        AgentResponse response = agentService.runAgent(
                AgentRequest.builder().message("Calculate and shout").build());

        assertThat(response.getToolCalls()).hasSize(2);
        assertThat(response.getToolCalls().get(0).getToolName()).isEqualTo("calculator");
        assertThat(response.getToolCalls().get(1).getToolName()).isEqualTo("string_utils");
        assertThat(response.getToolCalls().get(1).getOutput()).isEqualTo("HELLO WORLD");
    }

    @Test
    void runAgent_tokenUsage_aggregatedAcrossIterations() {
        ConverseResponse toolUseResponse = buildToolUseResponseWithUsage(
                "id-1", "calculator",
                Document.fromMap(Map.of("operation", Document.fromString("sqrt"), "a", Document.fromNumber(16))),
                buildUsage(50, 30)
        );
        ConverseResponse finalResponse = ConverseResponse.builder()
                .output(ConverseOutput.builder()
                        .message(Message.builder()
                                .role(ConversationRole.ASSISTANT)
                                .content(ContentBlock.fromText("Result: 4"))
                                .build())
                        .build())
                .stopReason(StopReason.END_TURN)
                .usage(buildUsage(40, 20))
                .build();

        when(bedrockClient.converse(any(ConverseRequest.class)))
                .thenReturn(toolUseResponse)
                .thenReturn(finalResponse);

        AgentResponse response = agentService.runAgent(
                AgentRequest.builder().message("Square root of 16").build());

        assertThat(response.getUsage().getInputTokens()).isEqualTo(90);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(50);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(140);
    }

    @Test
    void runAgent_bedrockThrows_wrapsInBedrockException() {
        when(bedrockClient.converse(any(ConverseRequest.class)))
                .thenThrow(new RuntimeException("Network error"));

        assertThatThrownBy(() -> agentService.runAgent(
                AgentRequest.builder().message("Hello").build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("Network error");
    }

    // ── executeTool: calculator ───────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "add,      10, 5,  15",
            "subtract, 10, 5,  5",
            "multiply, 6,  7,  42",
            "power,    2,  10, 1024"
    })
    void calculator_basicOperations_returnsCorrectResult(
            String op, double a, double b, String expected) {

        Document input = Document.fromMap(Map.of(
                "operation", Document.fromString(op),
                "a", Document.fromNumber(a),
                "b", Document.fromNumber(b)
        ));

        assertThat(agentService.executeTool("calculator", input)).isEqualTo(expected);
    }

    @Test
    void calculator_divide_returnsDecimal() {
        Document input = Document.fromMap(Map.of(
                "operation", Document.fromString("divide"),
                "a", Document.fromNumber(10),
                "b", Document.fromNumber(3)
        ));

        String result = agentService.executeTool("calculator", input);
        assertThat(Double.parseDouble(result)).isCloseTo(3.333, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void calculator_sqrt_noSecondOperand() {
        Document input = Document.fromMap(Map.of(
                "operation", Document.fromString("sqrt"),
                "a", Document.fromNumber(144)
        ));

        assertThat(agentService.executeTool("calculator", input)).isEqualTo("12");
    }

    @Test
    void calculator_modulo_returnsRemainder() {
        Document input = Document.fromMap(Map.of(
                "operation", Document.fromString("modulo"),
                "a", Document.fromNumber(17),
                "b", Document.fromNumber(5)
        ));

        assertThat(agentService.executeTool("calculator", input)).isEqualTo("2");
    }

    @Test
    void calculator_divideByZero_returnsError() {
        Document input = Document.fromMap(Map.of(
                "operation", Document.fromString("divide"),
                "a", Document.fromNumber(5),
                "b", Document.fromNumber(0)
        ));

        // executeToolSafely wraps exceptions; test direct executeTool throws
        assertThatThrownBy(() -> agentService.executeTool("calculator", input))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("zero");
    }

    // ── executeTool: get_current_time ─────────────────────────────────────────

    @Test
    void getCurrentTime_defaultFormat_returnsIso8601() {
        Document input = Document.fromMap(Map.of());
        String result = agentService.executeTool("get_current_time", input);
        // ISO 8601 contains a 'T' separator
        assertThat(result).contains("T");
    }

    @Test
    void getCurrentTime_dateOnlyFormat_returnsDateOnly() {
        Document input = Document.fromMap(Map.of(
                "format", Document.fromString("date_only")
        ));
        String result = agentService.executeTool("get_current_time", input);
        // date_only: yyyy-MM-dd (10 chars exactly)
        assertThat(result).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void getCurrentTime_invalidTimezone_defaultsToUtc() {
        Document input = Document.fromMap(Map.of(
                "timezone", Document.fromString("Not/AReal_Zone")
        ));
        // Should not throw; falls back to UTC
        assertThat(agentService.executeTool("get_current_time", input)).isNotBlank();
    }

    // ── executeTool: string_utils ─────────────────────────────────────────────

    @Test
    void stringUtils_uppercase_convertsToUppercase() {
        Document input = Document.fromMap(Map.of(
                "operation", Document.fromString("uppercase"),
                "text", Document.fromString("hello world")
        ));
        assertThat(agentService.executeTool("string_utils", input)).isEqualTo("HELLO WORLD");
    }

    @Test
    void stringUtils_reverse_reversesText() {
        Document input = Document.fromMap(Map.of(
                "operation", Document.fromString("reverse"),
                "text", Document.fromString("abcde")
        ));
        assertThat(agentService.executeTool("string_utils", input)).isEqualTo("edcba");
    }

    @Test
    void stringUtils_wordCount_countsWords() {
        Document input = Document.fromMap(Map.of(
                "operation", Document.fromString("word_count"),
                "text", Document.fromString("The quick brown fox")
        ));
        assertThat(agentService.executeTool("string_utils", input)).isEqualTo("4");
    }

    @Test
    void stringUtils_charCount_includesSpaces() {
        Document input = Document.fromMap(Map.of(
                "operation", Document.fromString("char_count"),
                "text", Document.fromString("hello")
        ));
        assertThat(agentService.executeTool("string_utils", input)).isEqualTo("5");
    }

    // ── executeTool: unit_converter ───────────────────────────────────────────

    @Test
    void unitConverter_celsiusToFahrenheit_convertsCorrectly() {
        Document input = Document.fromMap(Map.of(
                "value", Document.fromNumber(100),
                "from_unit", Document.fromString("celsius"),
                "to_unit", Document.fromString("fahrenheit")
        ));
        String result = agentService.executeTool("unit_converter", input);
        assertThat(result).contains("212.000000");
    }

    @Test
    void unitConverter_metersToFeet_convertsCorrectly() {
        Document input = Document.fromMap(Map.of(
                "value", Document.fromNumber(1),
                "from_unit", Document.fromString("meters"),
                "to_unit", Document.fromString("feet")
        ));
        String result = agentService.executeTool("unit_converter", input);
        // 1 meter ≈ 3.28084 feet
        assertThat(result).contains("3.28");
    }

    @Test
    void unitConverter_kilogramsToPounds_convertsCorrectly() {
        Document input = Document.fromMap(Map.of(
                "value", Document.fromNumber(1),
                "from_unit", Document.fromString("kilograms"),
                "to_unit", Document.fromString("pounds")
        ));
        String result = agentService.executeTool("unit_converter", input);
        // 1 kg ≈ 2.20462 lbs
        assertThat(result).contains("2.20");
    }

    @Test
    void unitConverter_crossCategory_throwsException() {
        Document input = Document.fromMap(Map.of(
                "value", Document.fromNumber(10),
                "from_unit", Document.fromString("celsius"),
                "to_unit", Document.fromString("meters")
        ));
        assertThatThrownBy(() -> agentService.executeTool("unit_converter", input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same category");
    }

    @Test
    void unknownTool_returnsUnknownToolMessage() {
        Document input = Document.fromMap(Map.of());
        String result = agentService.executeTool("nonexistent_tool", input);
        assertThat(result).contains("Unknown tool");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubEndTurnResponse(String text) {
        when(bedrockClient.converse(any(ConverseRequest.class)))
                .thenReturn(buildEndTurnResponse(text));
    }

    private ConverseResponse buildEndTurnResponse(String text) {
        return ConverseResponse.builder()
                .output(ConverseOutput.builder()
                        .message(Message.builder()
                                .role(ConversationRole.ASSISTANT)
                                .content(ContentBlock.fromText(text))
                                .build())
                        .build())
                .stopReason(StopReason.END_TURN)
                .usage(buildUsage(10, 5))
                .build();
    }

    private ConverseResponse buildToolUseResponse(String toolUseId, String toolName, Document input) {
        return buildToolUseResponseWithUsage(toolUseId, toolName, input, buildUsage(20, 15));
    }

    private ConverseResponse buildToolUseResponseWithUsage(
            String toolUseId, String toolName, Document input, TokenUsage usage) {

        ToolUseBlock toolUse = ToolUseBlock.builder()
                .toolUseId(toolUseId)
                .name(toolName)
                .input(input)
                .build();

        return ConverseResponse.builder()
                .output(ConverseOutput.builder()
                        .message(Message.builder()
                                .role(ConversationRole.ASSISTANT)
                                .content(ContentBlock.fromToolUse(toolUse))
                                .build())
                        .build())
                .stopReason(StopReason.TOOL_USE)
                .usage(usage)
                .build();
    }

    private TokenUsage buildUsage(int inputTokens, int outputTokens) {
        return TokenUsage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .build();
    }
}
