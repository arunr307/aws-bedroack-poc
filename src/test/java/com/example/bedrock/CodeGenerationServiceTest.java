package com.example.bedrock;

import com.example.bedrock.config.BedrockProperties;
import com.example.bedrock.exception.BedrockException;
import com.example.bedrock.model.*;
import com.example.bedrock.service.CodeGenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CodeGenerationService}.
 *
 * <p>The Bedrock client is mocked with pre-built JSON responses —
 * no real AWS calls are made.
 */
@ExtendWith(MockitoExtension.class)
class CodeGenerationServiceTest {

    @Mock
    private BedrockRuntimeClient bedrockClient;

    private CodeGenerationService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        BedrockProperties props = new BedrockProperties();
        props.setRegion("us-east-1");
        BedrockProperties.Bedrock bedrock = new BedrockProperties.Bedrock();
        bedrock.setModelId("amazon.nova-lite-v1:0");
        bedrock.setMaxTokens(4096);
        props.setBedrock(bedrock);
        service = new CodeGenerationService(bedrockClient, props, objectMapper);
    }

    // ── generate ─────────────────────────────────────────────────────────────

    @Test
    void generate_minimal_returnsCodeAndExplanation() throws Exception {
        stubResponse("""
                {
                  "code": "def add(a, b):\\n    return a + b",
                  "language": "Python",
                  "explanation": "Simple addition function.",
                  "dependencies": []
                }
                """);

        CodeGenerateResponse response = service.generate(CodeGenerateRequest.builder()
                .description("A function that adds two numbers")
                .language("Python")
                .build());

        assertThat(response.getCode()).contains("def add");
        assertThat(response.getLanguage()).isEqualTo("Python");
        assertThat(response.getExplanation()).isNotBlank();
        assertThat(response.getDependencies()).isEmpty();
        assertThat(response.getModelId()).isEqualTo("amazon.nova-lite-v1:0");
        assertThat(response.getUsage()).isNotNull();
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void generate_withFrameworkAndRequirements_returnsWithDependencies() throws Exception {
        stubResponse("""
                {
                  "code": "@RestController\\npublic class UserController { ... }",
                  "language": "Java",
                  "explanation": "Spring Boot REST controller for user creation.",
                  "dependencies": ["spring-web", "spring-validation"]
                }
                """);

        CodeGenerateResponse response = service.generate(CodeGenerateRequest.builder()
                .description("Create user REST endpoint")
                .language("Java")
                .framework("Spring Boot")
                .requirements(List.of("Validate request body", "Return 201"))
                .build());

        assertThat(response.getDependencies()).containsExactly("spring-web", "spring-validation");
    }

    @Test
    void generate_modelOverride_usesSpecifiedModel() throws Exception {
        stubResponse("""
                {"code": "...", "language": "Go", "explanation": "...", "dependencies": []}
                """);

        CodeGenerateResponse response = service.generate(CodeGenerateRequest.builder()
                .description("Hello world in Go")
                .language("Go")
                .modelId("amazon.nova-pro-v1:0")
                .build());

        assertThat(response.getModelId()).isEqualTo("amazon.nova-pro-v1:0");
    }

    // ── explain ───────────────────────────────────────────────────────────────

    @Test
    void explain_detailed_returnsKeyPointsAndComplexity() throws Exception {
        stubResponse("""
                {
                  "language": "Java",
                  "explanation": "This method computes Fibonacci numbers recursively.",
                  "keyPoints": ["Exponential time O(2^n)", "No memoization", "Base cases: 0 and 1"],
                  "complexity": "MODERATE"
                }
                """);

        CodeExplainResponse response = service.explain(CodeExplainRequest.builder()
                .code("int fib(int n) { return n <= 1 ? n : fib(n-1) + fib(n-2); }")
                .language("Java")
                .detailLevel(DetailLevel.DETAILED)
                .build());

        assertThat(response.getLanguage()).isEqualTo("Java");
        assertThat(response.getExplanation()).isNotBlank();
        assertThat(response.getKeyPoints()).hasSize(3);
        assertThat(response.getComplexity()).isEqualTo("MODERATE");
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void explain_brief_returnsShortExplanation() throws Exception {
        stubResponse("""
                {
                  "language": "SQL",
                  "explanation": "Joins users and orders, grouping by user to count orders.",
                  "keyPoints": [],
                  "complexity": "SIMPLE"
                }
                """);

        CodeExplainResponse response = service.explain(CodeExplainRequest.builder()
                .code("SELECT u.name, COUNT(o.id) FROM users u LEFT JOIN orders o ON u.id = o.user_id GROUP BY u.id")
                .language("SQL")
                .detailLevel(DetailLevel.BRIEF)
                .build());

        assertThat(response.getKeyPoints()).isEmpty();
        assertThat(response.getComplexity()).isEqualTo("SIMPLE");
    }

    // ── review ────────────────────────────────────────────────────────────────

    @Test
    void review_findsSecurityIssue_returnsIssueList() throws Exception {
        stubResponse("""
                {
                  "issues": [
                    {
                      "severity": "CRITICAL",
                      "category": "SECURITY",
                      "description": "SQL injection — user input concatenated directly into query.",
                      "lineReference": "line 2",
                      "suggestion": "Use parameterized queries."
                    }
                  ],
                  "summary": "Critical SQL injection vulnerability must be fixed before production.",
                  "overallRating": 2
                }
                """);

        CodeReviewResponse response = service.review(CodeReviewRequest.builder()
                .code("String q = \"SELECT * FROM users WHERE id = \" + id;")
                .language("Java")
                .build());

        assertThat(response.getIssues()).hasSize(1);
        assertThat(response.getIssues().get(0).getSeverity()).isEqualTo("CRITICAL");
        assertThat(response.getIssues().get(0).getCategory()).isEqualTo("SECURITY");
        assertThat(response.getIssues().get(0).getSuggestion()).isNotBlank();
        assertThat(response.getOverallRating()).isEqualTo(2);
        assertThat(response.getSummary()).isNotBlank();
    }

    @Test
    void review_cleanCode_returnsEmptyIssuesAndHighRating() throws Exception {
        stubResponse("""
                {
                  "issues": [],
                  "summary": "Code is clean and follows best practices.",
                  "overallRating": 9
                }
                """);

        CodeReviewResponse response = service.review(CodeReviewRequest.builder()
                .code("public int add(int a, int b) { return a + b; }")
                .language("Java")
                .build());

        assertThat(response.getIssues()).isEmpty();
        assertThat(response.getOverallRating()).isEqualTo(9);
    }

    @Test
    void review_withFocusAreas_limitsFocusToRequested() throws Exception {
        stubResponse("""
                {
                  "issues": [
                    {
                      "severity": "HIGH",
                      "category": "PERFORMANCE",
                      "description": "O(n^2) nested loop.",
                      "lineReference": "lines 3-7",
                      "suggestion": "Use a hash set for O(n) lookup."
                    }
                  ],
                  "summary": "Performance can be improved.",
                  "overallRating": 6
                }
                """);

        CodeReviewResponse response = service.review(CodeReviewRequest.builder()
                .code("for (int i=0; i<n; i++) for (int j=0; j<n; j++) { ... }")
                .focusAreas(List.of(ReviewFocus.PERFORMANCE))
                .build());

        assertThat(response.getIssues().get(0).getCategory()).isEqualTo("PERFORMANCE");
    }

    // ── convert ───────────────────────────────────────────────────────────────

    @Test
    void convert_pythonToGo_returnsConvertedCodeAndNotes() throws Exception {
        stubResponse("""
                {
                  "convertedCode": "func add(a, b int) int {\\n    return a + b\\n}",
                  "sourceLanguage": "Python",
                  "targetLanguage": "Go",
                  "notes": ["Added explicit types", "Removed def keyword"]
                }
                """);

        CodeConvertResponse response = service.convert(CodeConvertRequest.builder()
                .code("def add(a, b):\n    return a + b")
                .sourceLanguage("Python")
                .targetLanguage("Go")
                .build());

        assertThat(response.getConvertedCode()).contains("func add");
        assertThat(response.getSourceLanguage()).isEqualTo("Python");
        assertThat(response.getTargetLanguage()).isEqualTo("Go");
        assertThat(response.getNotes()).hasSize(2);
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void convert_autoDetectsSourceLanguage() throws Exception {
        stubResponse("""
                {
                  "convertedCode": "def divide(a, b):\\n    return a / b",
                  "sourceLanguage": "Java",
                  "targetLanguage": "Python",
                  "notes": ["Removed explicit types"]
                }
                """);

        CodeConvertResponse response = service.convert(CodeConvertRequest.builder()
                .code("public double divide(double a, double b) { return a / b; }")
                // sourceLanguage intentionally omitted — auto-detect
                .targetLanguage("Python")
                .build());

        assertThat(response.getSourceLanguage()).isEqualTo("Java");
    }

    // ── fix ───────────────────────────────────────────────────────────────────

    @Test
    void fix_withErrorMessage_returnsFixedCodeAndChanges() throws Exception {
        stubResponse("""
                {
                  "fixedCode": "public int divide(int a, int b) {\\n    if (b == 0) throw new ArithmeticException(\\"Division by zero\\");\\n    return a / b;\\n}",
                  "language": "Java",
                  "explanation": "Added guard clause for division by zero.",
                  "changes": ["Added null/zero guard for b before division"]
                }
                """);

        CodeFixResponse response = service.fix(CodeFixRequest.builder()
                .code("public int divide(int a, int b) { return a / b; }")
                .language("Java")
                .errorMessage("ArithmeticException: / by zero")
                .build());

        assertThat(response.getFixedCode()).contains("if (b == 0)");
        assertThat(response.getLanguage()).isEqualTo("Java");
        assertThat(response.getExplanation()).isNotBlank();
        assertThat(response.getChanges()).hasSize(1);
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void fix_withoutErrorMessage_generalFix() throws Exception {
        stubResponse("""
                {
                  "fixedCode": "def fib(n):\\n    if n <= 1: return n\\n    return fib(n-1) + fib(n-2)",
                  "language": "Python",
                  "explanation": "Added missing base case for n=0.",
                  "changes": ["Added base case: if n <= 1 return n"]
                }
                """);

        CodeFixResponse response = service.fix(CodeFixRequest.builder()
                .code("def fib(n): return fib(n-1) + fib(n-2)")
                .build());

        assertThat(response.getFixedCode()).isNotBlank();
        assertThat(response.getChanges()).isNotEmpty();
    }

    // ── parseJson ─────────────────────────────────────────────────────────────

    @Test
    void parseJson_fencedJson_stripsAndParses() throws Exception {
        String fenced = "```json\n{\"code\": \"print('hello')\", \"language\": \"Python\", \"explanation\": \"x\", \"dependencies\": []}\n```";
        var node = service.parseJson(fenced);
        assertThat(node.get("language").asText()).isEqualTo("Python");
    }

    @Test
    void parseJson_plainJson_parsesSuccessfully() throws Exception {
        String plain = "{\"code\": \"x = 1\", \"language\": \"Python\", \"explanation\": \"assigns\", \"dependencies\": []}";
        var node = service.parseJson(plain);
        assertThat(node.get("code").asText()).isEqualTo("x = 1");
    }

    @Test
    void parseJson_invalidJson_throwsBedrockException() {
        assertThatThrownBy(() -> service.parseJson("This is not JSON!"))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("unparseable JSON");
    }

    // ── error handling ────────────────────────────────────────────────────────

    @Test
    void generate_bedrockThrows_wrapsInBedrockException() {
        when(bedrockClient.converse(any(ConverseRequest.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> service.generate(CodeGenerateRequest.builder()
                .description("A function")
                .language("Java")
                .build()))
                .isInstanceOf(BedrockException.class)
                .hasMessageContaining("Bedrock code operation failed");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubResponse(String jsonText) {
        ContentBlock block = ContentBlock.fromText(jsonText);
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(block)
                .build();
        ConverseOutput output = ConverseOutput.builder().message(message).build();
        TokenUsage usage = TokenUsage.builder()
                .inputTokens(200).outputTokens(150).totalTokens(350).build();
        ConverseResponse response = ConverseResponse.builder()
                .output(output).usage(usage).stopReason(StopReason.END_TURN).build();
        when(bedrockClient.converse(any(ConverseRequest.class))).thenReturn(response);
    }
}
