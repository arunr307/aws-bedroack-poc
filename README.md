# AWS Bedrock POC — Java / Spring Boot

A proof-of-concept Spring Boot application that integrates with
[Amazon Bedrock](https://aws.amazon.com/bedrock/) to expose a conversational
**Chat REST API** backed by foundation models (Claude, Titan, etc.).

---

## Table of contents

1. [Architecture overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Configuration](#configuration)
4. [Running the application](#running-the-application)
5. [Chat API reference](#chat-api-reference)
6. [Example cURL calls](#example-curl-calls)
7. [Project structure](#project-structure)
8. [Running tests](#running-tests)
9. [Supported Bedrock models](#supported-bedrock-models)
10. [Roadmap](#roadmap)

---

## Architecture overview

```
Client (curl / Postman / UI)
        │
        ▼
┌──────────────────────────────────────────┐
│          Spring Boot (port 8080)         │
│                                          │
│  ChatController  ─▶  ChatService         │
│                          │               │
│                    BedrockRuntimeClient  │
└──────────────────────────────────────────┘
                           │
                           ▼
              Amazon Bedrock (Converse API)
                           │
                           ▼
               Foundation Model (Claude, Titan…)
```

The application uses the **Bedrock Converse API** — a unified interface that
works across all foundation models without requiring model-specific payload
formats.

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21+ |
| Maven | 3.9+ |
| AWS Account | with Bedrock model access enabled |

Enable model access in the AWS Console:
**Bedrock → Model access → Manage model access → select your model → Save changes**

---

## Configuration

All settings live in [`src/main/resources/application.yml`](src/main/resources/application.yml).

```yaml
aws:
  region: us-east-1                       # Region where Bedrock is enabled

  credentials:
    access-key: YOUR_AWS_ACCESS_KEY_ID    # Replace with your key
    secret-key: YOUR_AWS_SECRET_ACCESS_KEY

  bedrock:
    model-id: anthropic.claude-3-5-sonnet-20241022-v2:0   # Model to use
    max-tokens: 2048          # Max tokens per response
    temperature: 0.7          # 0.0 = deterministic, 1.0 = creative
    max-conversation-turns: 10 # Max past turns to keep in memory
```

### Credential options (most secure first)

| Option | How |
|--------|-----|
| IAM Role (recommended for prod) | Leave `access-key` blank; attach a role to your EC2/ECS/Lambda |
| Environment variables | Set `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` |
| AWS profile | Leave `access-key` blank; configure `~/.aws/credentials` |
| Static keys in YAML | Fill in `access-key` / `secret-key` (local dev only) |

When `access-key` is left blank (or unchanged from the placeholder), the app
automatically falls back to the SDK's **DefaultCredentialsProvider** chain.

---

## Running the application

```bash
# 1. Clone / enter the project
cd aws-bedrock-poc

# 2. Update application.yml with your AWS credentials

# 3. Build and run
JAVA_HOME=/opt/homebrew/opt/openjdk@17 mvn spring-boot:run

# The API is now available at http://localhost:8080
```

> **Note (macOS / Homebrew):** If your shell's default `java` is not Java 17,
> prefix Maven commands with `JAVA_HOME=/opt/homebrew/opt/openjdk@17`.
> This ensures the Lombok annotation processor is compatible with the compiler.

### Build a JAR

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 mvn clean package -DskipTests
java -jar target/aws-bedrock-poc-1.0.0-SNAPSHOT.jar
```

---

## Chat API reference

### `POST /api/chat`

Send a message to the configured foundation model.

#### Request body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `message` | `string` | Yes | The user's message (max 100 000 chars) |
| `conversationHistory` | `ChatMessage[]` | No | Prior turns for multi-turn conversations |
| `modelId` | `string` | No | Override the default model for this request only |
| `systemPrompt` | `string` | No | Sets the model's persona / behaviour |

#### `ChatMessage` object

```json
{ "role": "user" | "assistant", "content": "..." }
```

#### Response body

```json
{
  "reply": "The model's response text",
  "modelId": "anthropic.claude-3-5-sonnet-20241022-v2:0",
  "usage": {
    "inputTokens": 42,
    "outputTokens": 88,
    "totalTokens": 130
  },
  "conversationHistory": [
    { "role": "user",      "content": "Hello!" },
    { "role": "assistant", "content": "Hi there!" }
  ],
  "timestamp": "2025-04-16T10:30:00Z"
}
```

---

### `GET /api/chat/health`

Returns `200 OK` with the string `Chat service is running`.

### `GET /actuator/health`

Spring Boot Actuator health endpoint — returns full service health details.

---

## Example cURL calls

### Stateless (single-turn)

```bash
curl -X POST http://localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d '{
           "message": "What is Amazon Bedrock?"
         }'
```

### With a system prompt

```bash
curl -X POST http://localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d '{
           "message": "What services should I use for a serverless API?",
           "systemPrompt": "You are an AWS Solutions Architect. Answer concisely."
         }'
```

### Multi-turn conversation

```bash
# Turn 1 — save the conversationHistory from the response
RESPONSE=$(curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{ "message": "What is AWS Lambda?" }')

echo $RESPONSE | jq .reply

# Turn 2 — pass the previous history back
curl -X POST http://localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d "{
           \"message\": \"How does it differ from EC2?\",
           \"conversationHistory\": $(echo $RESPONSE | jq .conversationHistory)
         }"
```

### Override the model per request

```bash
curl -X POST http://localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d '{
           "message": "Tell me a joke.",
           "modelId": "anthropic.claude-3-haiku-20240307-v1:0"
         }'
```

---

## Project structure

```
aws-bedrock-poc/
├── src/
│   ├── main/
│   │   ├── java/com/example/bedrock/
│   │   │   ├── BedrockPocApplication.java     # Entry point
│   │   │   ├── config/
│   │   │   │   ├── BedrockConfig.java         # AWS SDK client bean
│   │   │   │   └── BedrockProperties.java     # Typed config (application.yml)
│   │   │   ├── controller/
│   │   │   │   └── ChatController.java        # REST endpoints
│   │   │   ├── service/
│   │   │   │   └── ChatService.java           # Bedrock Converse API logic
│   │   │   ├── model/
│   │   │   │   ├── ChatMessage.java           # Role + content pair
│   │   │   │   ├── ChatRequest.java           # POST body
│   │   │   │   └── ChatResponse.java          # Response body
│   │   │   └── exception/
│   │   │       ├── BedrockException.java      # Checked Bedrock errors
│   │   │       └── GlobalExceptionHandler.java # RFC 7807 error responses
│   │   └── resources/
│   │       └── application.yml                # All configuration
│   └── test/
│       └── java/com/example/bedrock/
│           └── ChatServiceTest.java           # Unit tests (mocked Bedrock)
├── pom.xml
└── README.md
```

---

## Running tests

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 mvn test
```

Tests mock the Bedrock client so no AWS credentials are needed to run them.

---

## Supported Bedrock models

> **Cross-region inference prefix:** Claude 3.5 and later require the `us.` prefix,
> which lets Bedrock automatically route your request to any US region with spare
> capacity — improving availability and reducing throttling.

**Amazon Nova** — no Anthropic use-case form required:

| Model | Model ID | Notes |
|-------|----------|-------|
| Nova Lite **(default)** | `amazon.nova-lite-v1:0` | Balanced speed & quality |
| Nova Micro | `amazon.nova-micro-v1:0` | Fastest, text-only |
| Nova Pro | `amazon.nova-pro-v1:0` | Highest quality |

**Anthropic Claude** — requires filling out the [Anthropic use-case form](https://console.aws.amazon.com/bedrock/home#/modelaccess) in AWS Console first:

| Model | Model ID |
|-------|----------|
| Claude 3.7 Sonnet | `us.anthropic.claude-3-7-sonnet-20250219-v1:0` |
| Claude 3.5 Haiku | `us.anthropic.claude-3-5-haiku-20241022-v1:0` |

> Enable model access in the AWS Console before use:
> **Bedrock → Model access → Manage model access**

---

## Roadmap

- [ ] **Step 1 — Chat API** ✅ (this release)
- [ ] **Step 2 — Streaming responses** (`ConverseStream` API for real-time token streaming)
- [ ] **Step 3 — RAG (Retrieval-Augmented Generation)** with Bedrock Knowledge Bases
- [ ] **Step 4 — Agents** using Bedrock Agents for tool use / function calling
- [ ] **Step 5 — Image generation** via Stable Diffusion / Titan Image models
