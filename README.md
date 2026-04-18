# AWS Bedrock POC — Java / Spring Boot

A proof-of-concept Spring Boot application that integrates with
[Amazon Bedrock](https://aws.amazon.com/bedrock/) to demonstrate real-world
AI use cases via a clean REST API — without any model-specific payload formats.

---

## Table of contents

1. [Architecture overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Local setup](#local-setup)
4. [Configuration reference](#configuration-reference)
5. [API — Chat](#api--chat)
6. [API — Streaming Chat](#api--streaming-chat)
7. [API — Text Summarization](#api--text-summarization)
8. [API — Embeddings](#api--embeddings)
9. [API — RAG (Retrieval-Augmented Generation)](#api--rag-retrieval-augmented-generation)
10. [API — Document Analysis](#api--document-analysis)
11. [API — Code Generation](#api--code-generation)
12. [API — Managed Knowledge Base (RAG)](#api--managed-knowledge-base-rag)
13. [API — Agent Chat (Tool / Function Calling)](#api--agent-chat-tool--function-calling)
14. [API — Image Generation](#api--image-generation)
15. [Project structure](#project-structure)
16. [Running tests](#running-tests)
17. [Supported Bedrock models](#supported-bedrock-models)
18. [Roadmap](#roadmap)

---

## Architecture overview

```
Client (curl / Postman / UI)
              │
              ▼
┌────────────────────────────────────────────────────────────┐
│                   Spring Boot  :8080                       │
│                                                            │
│  ChatController         ──▶  ChatService                   │
│  ChatController         ──▶  StreamingChatService          │
│  SummarizationController──▶  SummarizationService          │
│  EmbeddingController    ──▶  EmbeddingService              │
│  RagController          ──▶  RagService                    │
│                               │   └──▶ EmbeddingService    │
│                               └──▶ DocumentStore           │
│  DocumentAnalysisController──▶  DocumentAnalysisService    │
│  CodeGenerationController  ──▶  CodeGenerationService      │
│  KnowledgeBaseController   ──▶  KnowledgeBaseService       │
│  AgentController           ──▶  AgentService               │
│                                    │  (agentic tool loop)  │
│  ImageGenerationController ──▶  ImageGenerationService     │
│                     BedrockRuntimeClient  (sync)           │
│                     BedrockRuntimeAsyncClient (streaming)  │
│                     BedrockAgentRuntimeClient (KB)         │
└────────────────────────────────────────────────────────────┘
                                │
              ┌─────────────────┼──────────────────────────┐
              ▼                 ▼                  ▼        ▼
       Converse API       ConverseStream      InvokeModel  Agent Runtime
    (Chat, Summarize,     (Stream Chat)       (Embeddings) (Knowledge Bases)
    RAG gen, Analysis,                        RAG chunks)
    Code Generation)
              │                 │                  │        │
              └─────────────────┴──────────────────┴────────┘
                                ▼
            Foundation Model (Nova Lite / Titan Embed / Claude …)
```

| API | Bedrock call | Used by |
|-----|-------------|---------|
| Converse API | `client.converse()` | Chat, Summarization, RAG generation, Document Analysis, Code Generation, Agent (tool loop) |
| ConverseStream API | `asyncClient.converseStream()` | Streaming Chat |
| InvokeModel API | `client.invokeModel()` | Embeddings, RAG chunk embedding, Image Generation |
| Agent Runtime API | `agentRuntimeClient.retrieveAndGenerate()` / `.retrieve()` | Managed Knowledge Bases |

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java (JDK) | 17 | On macOS/Homebrew: `/opt/homebrew/opt/openjdk@17` |
| Maven | 3.9+ | Install via `brew install maven` |
| AWS Account | — | With Bedrock model access enabled (see below) |

---

## Local setup

### 1 — Clone the project

```bash
git clone <repo-url>
cd aws-bedroack-poc
```

### 2 — Enable Bedrock model access in AWS Console

Amazon Nova models work out of the box (no extra form).
Anthropic Claude models require an additional use-case form.

1. Open **AWS Console → Bedrock → Model access**
2. Click **Manage model access**
3. Tick **Amazon Nova Lite** (and any other model you want)
4. Click **Save changes** — access is usually granted within seconds

For **Anthropic Claude** models:
- Tick any Claude model — you will be prompted to fill out the Anthropic use-case form
- Approval takes up to 15 minutes

### 3 — Add your AWS credentials to VS Code launch config

Open [`.vscode/launch.json`](.vscode/launch.json) and fill in your values:

```json
"env": {
    "AWS_ACCESS_KEY_ID":     "YOUR_ACCESS_KEY",
    "AWS_SECRET_ACCESS_KEY": "YOUR_SECRET_KEY",
    "AWS_REGION":            "us-east-1"
}
```

> **Security:** `.vscode/launch.json` is in `.gitignore` and will never be committed.
> `application.yml` references these as `${AWS_ACCESS_KEY_ID}` — no secrets in source code.

### 4 — Run from VS Code

Open `BedrockPocApplication.java` → click **Run** (or use the **BedrockPocApplication**
launch configuration in the Run & Debug panel).

### 4 (alternative) — Run from terminal

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 mvn spring-boot:run
```

> **macOS / Homebrew note:** Prefix all Maven commands with
> `JAVA_HOME=/opt/homebrew/opt/openjdk@17` if your default `java` is not version 17.
> This prevents a Lombok annotation-processor incompatibility.

### 5 — Verify the server is up

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP", ...}

curl http://localhost:8080/api/chat/health
# Chat service is running
```

### Build a runnable JAR

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 mvn clean package -DskipTests
java -jar target/aws-bedrock-poc-1.0.0-SNAPSHOT.jar
```

---

## Configuration reference

All settings are in [`src/main/resources/application.yml`](src/main/resources/application.yml).
Credentials are injected from environment variables — never hard-coded.

```yaml
aws:
  region: ${AWS_REGION:us-east-1}       # override with AWS_REGION env var

  credentials:
    access-key: ${AWS_ACCESS_KEY_ID:}   # set in .vscode/launch.json or env
    secret-key: ${AWS_SECRET_ACCESS_KEY:}

  bedrock:
    model-id: amazon.nova-lite-v1:0     # default model for all features
    max-tokens: 2048                    # max tokens per response
    temperature: 0.7                    # 0.0 = deterministic, 1.0 = creative
    max-conversation-turns: 10          # turns kept in chat history
```

### Credential resolution order (most secure first)

| Option | How |
|--------|-----|
| IAM Role (prod) | Attach role to EC2/ECS/Lambda — leave `access-key` blank |
| Environment variables | `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` |
| `.vscode/launch.json` | Local dev — injected by VS Code at launch time |
| AWS `~/.aws/credentials` profile | Leave `access-key` blank |

---

## API — Chat

### `POST /api/chat`

Send a message and receive the full reply in one response.

#### Request

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `message` | `string` | Yes | User's message (max 100 000 chars) |
| `conversationHistory` | `ChatMessage[]` | No | Previous turns for multi-turn context |
| `systemPrompt` | `string` | No | Sets the model's persona / behaviour |
| `modelId` | `string` | No | Override the default model for this request |

`ChatMessage` shape: `{ "role": "user" | "assistant", "content": "..." }`

#### Response

```json
{
  "reply": "Amazon Bedrock is a fully managed service...",
  "modelId": "amazon.nova-lite-v1:0",
  "usage": { "inputTokens": 12, "outputTokens": 88, "totalTokens": 100 },
  "conversationHistory": [
    { "role": "user",      "content": "What is AWS Bedrock?" },
    { "role": "assistant", "content": "Amazon Bedrock is..." }
  ],
  "timestamp": "2025-04-16T22:00:00Z"
}
```

#### Examples

**Single-turn:**
```bash
curl -X POST http://localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d '{ "message": "What is AWS Bedrock?" }'
```

**With a system prompt:**
```bash
curl -X POST http://localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d '{
           "message": "What services should I use for a serverless API?",
           "systemPrompt": "You are an AWS Solutions Architect. Be concise."
         }'
```

**Multi-turn (pass history back each time):**
```bash
# Turn 1
RESP=$(curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{ "message": "What is AWS Lambda?" }')

echo $RESP | jq .reply

# Turn 2 — include history from the previous response
curl -X POST http://localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d "{
           \"message\": \"How does it compare to ECS?\",
           \"conversationHistory\": $(echo $RESP | jq .conversationHistory)
         }"
```

**Override the model per request:**
```bash
curl -X POST http://localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d '{ "message": "Explain quantum computing.", "modelId": "amazon.nova-pro-v1:0" }'
```

---

### `POST /api/chat/stream`  _(Server-Sent Events)_

Same request body as `POST /api/chat`. The HTTP connection stays open and tokens
are pushed one by one as the model generates them.

#### Event format

```
data: {"token":"Amazon","done":false}
data: {"token":" Bedrock","done":false}
data: {"token":" is","done":false}
...
data: {"token":"","done":true,"modelId":"amazon.nova-lite-v1:0",
        "usage":{"inputTokens":12,"outputTokens":88,"totalTokens":100}}
```

#### Example

```bash
# -N disables curl's output buffering so tokens appear immediately
curl -N -X POST http://localhost:8080/api/chat/stream \
     -H "Content-Type: application/json" \
     -d '{ "message": "Explain AWS Lambda in simple terms." }'
```

---

### `GET /api/chat/health`

Returns `200 OK` — `Chat service is running`

---

## API — Text Summarization

### `POST /api/summarize`

Summarize any text using one of five built-in styles.
The model is guided by a style-specific system prompt so you don't need to craft
prompts yourself — just pick a style and send the text.

#### Request

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `text` | `string` | Yes | — | Text to summarize (max 100 000 chars / ~25 000 words) |
| `style` | `string` | No | `BRIEF` | One of the five styles below |
| `maxWords` | `integer` | No | model decides | Approximate word limit for the summary |
| `language` | `string` | No | `"English"` | Output language (e.g. `"Spanish"`, `"French"`) |
| `focusOn` | `string` | No | — | Aspect to emphasise (e.g. `"security risks"`) |
| `modelId` | `string` | No | config default | Override model for this request only |

#### Summary styles

| Style | Output |
|-------|--------|
| `BRIEF` | 2–3 sentence high-level overview |
| `DETAILED` | Multi-paragraph summary preserving key details |
| `BULLET_POINTS` | Key points as a concise bulleted list |
| `HEADLINE` | Single one-line title (max ~15 words) |
| `EXECUTIVE` | Business summary: Overview + Key Points + Recommendation |

#### Response

```json
{
  "summary": "AWS Bedrock is a managed service providing API access to foundation models...",
  "style": "BRIEF",
  "originalWordCount": 1250,
  "summaryWordCount": 42,
  "compressionRatio": 29.8,
  "modelId": "amazon.nova-lite-v1:0",
  "usage": { "inputTokens": 1380, "outputTokens": 55, "totalTokens": 1435 },
  "timestamp": "2025-04-16T22:00:00Z"
}
```

#### Examples

**Default brief summary:**
```bash
curl -X POST http://localhost:8080/api/summarize \
     -H "Content-Type: application/json" \
     -d '{
           "text": "Amazon Bedrock is a fully managed service that makes high-performing
                    foundation models from Amazon and leading AI companies available
                    through a unified API..."
         }'
```

**Bullet-point summary:**
```bash
curl -X POST http://localhost:8080/api/summarize \
     -H "Content-Type: application/json" \
     -d '{
           "text": "YOUR_LONG_TEXT_HERE",
           "style": "BULLET_POINTS"
         }'
```

**Executive summary with focus area:**
```bash
curl -X POST http://localhost:8080/api/summarize \
     -H "Content-Type: application/json" \
     -d '{
           "text": "YOUR_LONG_TEXT_HERE",
           "style": "EXECUTIVE",
           "focusOn": "cost savings and ROI"
         }'
```

**Summarize in a different language:**
```bash
curl -X POST http://localhost:8080/api/summarize \
     -H "Content-Type: application/json" \
     -d '{
           "text": "YOUR_LONG_TEXT_HERE",
           "style": "BRIEF",
           "language": "Spanish"
         }'
```

**Summarize a file:**
```bash
curl -X POST http://localhost:8080/api/summarize \
     -H "Content-Type: application/json" \
     -d "{\"text\": $(jq -Rs . < my-document.txt), \"style\": \"DETAILED\"}"
```

**Word-limited summary with a specific model:**
```bash
curl -X POST http://localhost:8080/api/summarize \
     -H "Content-Type: application/json" \
     -d '{
           "text": "YOUR_LONG_TEXT_HERE",
           "style": "BRIEF",
           "maxWords": 50,
           "modelId": "amazon.nova-pro-v1:0"
         }'
```

---

### `GET /api/summarize/styles`

Returns the list of all available styles with descriptions — useful for populating
a UI dropdown.

```bash
curl http://localhost:8080/api/summarize/styles
```

```json
[
  { "style": "BRIEF",         "description": "2–3 sentence high-level overview" },
  { "style": "DETAILED",      "description": "Multi-paragraph summary preserving key details" },
  { "style": "BULLET_POINTS", "description": "Key points formatted as a bulleted list" },
  { "style": "HEADLINE",      "description": "Single one-line title / headline" },
  { "style": "EXECUTIVE",     "description": "Business executive summary with structured takeaways" }
]
```

---

### `GET /actuator/health`

Spring Boot Actuator — full service health details.

---

## API — Embeddings

Embeddings convert text into numerical vectors that capture semantic meaning.
Texts with similar meaning produce vectors that are close together in vector space —
enabling similarity search, clustering, and retrieval without keyword matching.

> **Model:** Uses `InvokeModel` API (not Converse) — embeddings produce vectors,
> not conversational replies. Default model: `amazon.titan-embed-text-v2:0`.

### Local setup for Embeddings

1. Enable **Amazon Titan Embed Text V2** in AWS Console:
   **Bedrock → Model access → Manage model access → tick Titan Embed Text V2 → Save**
2. No extra form required — available immediately after enabling.

### `POST /api/embeddings/embed`

Convert text into a float vector for downstream similarity search or clustering.

#### Request

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `text` | `string` | Yes | — | Text to embed (max 50 000 chars / ~8 000 tokens) |
| `dimensions` | `integer` | No | `1024` | Vector size: `256`, `512`, or `1024` |
| `normalize` | `boolean` | No | `true` | L2-normalise the vector (recommended) |
| `modelId` | `string` | No | config | Override embedding model |

#### Response

```json
{
  "embedding": [0.023, -0.141, 0.087, "... 1024 values total"],
  "dimensions": 1024,
  "inputTokenCount": 12,
  "modelId": "amazon.titan-embed-text-v2:0",
  "timestamp": "2025-04-16T22:00:00Z"
}
```

#### Examples

**Get an embedding vector:**
```bash
curl -X POST http://localhost:8080/api/embeddings/embed \
     -H "Content-Type: application/json" \
     -d '{ "text": "AWS Lambda is a serverless compute service." }'
```

**Compact 256-dimension vector (faster, cheaper):**
```bash
curl -X POST http://localhost:8080/api/embeddings/embed \
     -H "Content-Type: application/json" \
     -d '{ "text": "AWS Lambda is a serverless compute service.", "dimensions": 256 }'
```

---

### `POST /api/embeddings/similarity`

Embed two texts and return their cosine similarity score in one call.

#### Request

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `textA` | `string` | Yes | First text (max 50 000 chars) |
| `textB` | `string` | Yes | Second text (max 50 000 chars) |
| `modelId` | `string` | No | Override embedding model |

#### Response

```json
{
  "score": 0.9421,
  "interpretation": "Very similar",
  "textA": "AWS Lambda is a serverless compute service.",
  "textB": "Lambda lets you run functions without provisioning servers.",
  "modelId": "amazon.titan-embed-text-v2:0",
  "timestamp": "2025-04-16T22:00:00Z"
}
```

#### Score interpretation

| Range | Meaning |
|-------|---------|
| 0.90 – 1.00 | Very similar / near-duplicate |
| 0.75 – 0.90 | Closely related |
| 0.50 – 0.75 | Somewhat related |
| 0.25 – 0.50 | Weakly related |
| < 0.25 | Unrelated / opposite |

#### Examples

**Compare two sentences:**
```bash
curl -X POST http://localhost:8080/api/embeddings/similarity \
     -H "Content-Type: application/json" \
     -d '{
           "textA": "AWS Lambda is a serverless compute service.",
           "textB": "Lambda lets you run functions without provisioning servers."
         }'
```

**Check for near-duplicates:**
```bash
curl -X POST http://localhost:8080/api/embeddings/similarity \
     -H "Content-Type: application/json" \
     -d '{
           "textA": "The meeting is scheduled for Monday at 10am.",
           "textB": "We will meet on Monday morning at ten o'\''clock."
         }'
```

**Compare unrelated texts:**
```bash
curl -X POST http://localhost:8080/api/embeddings/similarity \
     -H "Content-Type: application/json" \
     -d '{
           "textA": "AWS Lambda is a serverless compute service.",
           "textB": "The French Revolution began in 1789."
         }'
```

---

### `POST /api/embeddings/search`

Semantic search over a list of documents — no vector database required.
The query and every document are embedded, then ranked by cosine similarity.

#### Request

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `query` | `string` | Yes | — | Search query (max 10 000 chars) |
| `documents` | `string[]` | Yes | — | Corpus to search (max 100 documents) |
| `topK` | `integer` | No | `5` | Max results to return |
| `minScore` | `double` | No | `0.0` | Exclude results below this score |
| `modelId` | `string` | No | config | Override embedding model |

#### Response

```json
{
  "query": "serverless compute options on AWS",
  "results": [
    { "rank": 1, "score": 0.9421, "document": "AWS Lambda runs code without servers.", "documentIndex": 0 },
    { "rank": 2, "score": 0.8834, "document": "AWS Fargate is serverless containers on ECS/EKS.", "documentIndex": 2 },
    { "rank": 3, "score": 0.7102, "document": "AWS Step Functions orchestrates serverless workflows.", "documentIndex": 4 }
  ],
  "totalDocuments": 5,
  "returnedResults": 3,
  "modelId": "amazon.titan-embed-text-v2:0",
  "timestamp": "2025-04-16T22:00:00Z"
}
```

#### Examples

**Basic semantic search:**
```bash
curl -X POST http://localhost:8080/api/embeddings/search \
     -H "Content-Type: application/json" \
     -d '{
           "query": "serverless compute options on AWS",
           "documents": [
             "AWS Lambda runs code without provisioning or managing servers.",
             "Amazon EC2 provides resizable virtual machines in the cloud.",
             "AWS Fargate is serverless containers on ECS or EKS.",
             "Amazon S3 stores objects and files at any scale.",
             "AWS Step Functions orchestrates distributed serverless workflows."
           ],
           "topK": 3
         }'
```

**With a minimum relevance threshold:**
```bash
curl -X POST http://localhost:8080/api/embeddings/search \
     -H "Content-Type: application/json" \
     -d '{
           "query": "how to save costs on AWS",
           "documents": [
             "Use Reserved Instances for predictable workloads to save up to 72%.",
             "Spot Instances offer up to 90% savings for fault-tolerant workloads.",
             "Amazon S3 Intelligent-Tiering moves data to cheaper tiers automatically.",
             "AWS Lambda charges only for actual compute time used.",
             "Amazon RDS provides managed relational databases."
           ],
           "topK": 10,
           "minScore": 0.6
         }'
```

**Search a file corpus:**
```bash
# Load documents from a JSON array file
curl -X POST http://localhost:8080/api/embeddings/search \
     -H "Content-Type: application/json" \
     -d "{\"query\": \"machine learning\", \"documents\": $(cat docs.json), \"topK\": 5}"
```

---

## API — RAG (Retrieval-Augmented Generation)

RAG grounds a language model's answers in your own documents — eliminating
hallucination by constraining the model to only what is in the provided context.

This implementation is a **DIY in-memory pipeline** — no Bedrock Knowledge Bases,
no vector database, no extra infrastructure. Everything runs inside the Spring Boot
process, making it ideal for POCs and small corpora (hundreds of documents).

### How it works

```
INGEST                          QUERY
──────                          ─────
documents                       question
   │                               │
   ▼                               ▼
split into overlapping          embed question
word-based chunks               (Titan Embed V2)
   │                               │
   ▼                               ▼
embed each chunk                find top-K similar chunks
(Titan Embed V2)                (cosine similarity scan)
   │                               │
   ▼                               ▼
store chunks + vectors          inject chunks as numbered context
(in-memory ConcurrentHashMap)   into system prompt
                                   │
                                   ▼
                                Bedrock Converse API
                                (Nova Lite by default)
                                   │
                                   ▼
                                answer + source citations
```

### Local setup for RAG

Both **Amazon Nova Lite** (generation) and **Titan Embed Text V2** (embedding)
must be enabled in your AWS account:

1. Open **AWS Console → Bedrock → Model access → Manage model access**
2. Tick **Amazon Nova Lite** and **Amazon Titan Embed Text V2**
3. Click **Save changes**

---

### `POST /api/rag/ingest`

Chunks, embeds, and stores one or more documents in the in-memory knowledge base.
Returns IDs for each document — use them to delete individual documents later.

#### Request

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `documents` | `DocumentInput[]` | Yes | — | Max 50 documents per request |
| `chunkSize` | `integer` | No | `200` | Target words per chunk |
| `chunkOverlap` | `integer` | No | `20` | Words shared between consecutive chunks |

`DocumentInput` shape: `{ "title": "...", "content": "..." }` (content max 500 000 chars)

**Chunking tip:** Smaller chunks (100–150 words) improve retrieval precision;
larger chunks (300–400 words) preserve more context per result. Overlap (10–15%
of `chunkSize`) prevents context loss at chunk boundaries.

#### Response

```json
{
  "documentIds": ["d1a2b3c4-...", "e5f6a7b8-..."],
  "ingestedDocuments": 2,
  "totalChunks": 18,
  "embeddingModel": "amazon.titan-embed-text-v2:0",
  "timestamp": "2025-04-17T09:00:00Z"
}
```

#### Examples

**Ingest a single document:**
```bash
curl -X POST http://localhost:8080/api/rag/ingest \
     -H "Content-Type: application/json" \
     -d '{
           "documents": [
             {
               "title": "AWS Lambda Overview",
               "content": "AWS Lambda is a serverless, event-driven compute service that lets you run code for virtually any type of application or backend service without provisioning or managing servers. Lambda runs your code on a high-availability compute infrastructure and performs all of the administration of the compute resources, including server and operating system maintenance, capacity provisioning and automatic scaling, and logging."
             }
           ]
         }'
```

**Ingest multiple documents with custom chunking:**
```bash
curl -X POST http://localhost:8080/api/rag/ingest \
     -H "Content-Type: application/json" \
     -d '{
           "documents": [
             { "title": "Lambda Overview", "content": "AWS Lambda is serverless compute..." },
             { "title": "ECS Overview",    "content": "Amazon ECS is a container service..." },
             { "title": "EKS Overview",    "content": "Amazon EKS is managed Kubernetes..." }
           ],
           "chunkSize": 150,
           "chunkOverlap": 15
         }'
```

**Ingest a local file:**
```bash
curl -X POST http://localhost:8080/api/rag/ingest \
     -H "Content-Type: application/json" \
     -d "{
           \"documents\": [
             { \"title\": \"My Document\", \"content\": $(jq -Rs . < my-document.txt) }
           ]
         }"
```

---

### `POST /api/rag/query`

Answers a question using the ingested knowledge base.
The model is constrained to only information in the retrieved context — it will
say "I don't know" rather than fabricating an answer not in your documents.

#### Request

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `question` | `string` | Yes | — | The question to answer (max 10 000 chars) |
| `topK` | `integer` | No | `5` | Max chunks to retrieve as context |
| `minScore` | `double` | No | `0.5` | Minimum cosine similarity (0–1) |
| `modelId` | `string` | No | config | Override generation model |
| `systemPrompt` | `string` | No | built-in grounding prompt | Custom system prompt |

**Tuning retrieval:**
- Raise `minScore` (e.g. `0.7`) to get only highly relevant chunks — reduces noise
- Lower `minScore` (e.g. `0.3`) to cast a wider net — useful for broad questions
- Increase `topK` for richer context at the cost of more tokens

#### Response

```json
{
  "question": "What is AWS Lambda?",
  "answer": "AWS Lambda is a serverless, event-driven compute service that lets you run code without provisioning or managing servers. It runs your code on high-availability infrastructure and handles all administration automatically.",
  "sources": [
    {
      "documentId": "d1a2b3c4-...",
      "title": "AWS Lambda Overview",
      "chunkText": "AWS Lambda is a serverless, event-driven compute service...",
      "score": 0.9421,
      "chunkIndex": 0
    }
  ],
  "retrievedChunks": 1,
  "generationModelId": "amazon.nova-lite-v1:0",
  "embeddingModelId": "amazon.titan-embed-text-v2:0",
  "usage": { "inputTokens": 540, "outputTokens": 82, "totalTokens": 622 },
  "timestamp": "2025-04-17T09:00:00Z"
}
```

#### Examples

**Basic question:**
```bash
curl -X POST http://localhost:8080/api/rag/query \
     -H "Content-Type: application/json" \
     -d '{ "question": "What is AWS Lambda?" }'
```

**With retrieval tuning:**
```bash
curl -X POST http://localhost:8080/api/rag/query \
     -H "Content-Type: application/json" \
     -d '{
           "question": "How does Lambda handle scaling?",
           "topK": 8,
           "minScore": 0.6
         }'
```

**With a custom system prompt:**
```bash
curl -X POST http://localhost:8080/api/rag/query \
     -H "Content-Type: application/json" \
     -d '{
           "question": "What are the cost implications of Lambda vs ECS?",
           "systemPrompt": "You are an AWS cost optimization expert. Be precise and cite pricing details from the context.",
           "topK": 5
         }'
```

**Using a more capable model:**
```bash
curl -X POST http://localhost:8080/api/rag/query \
     -H "Content-Type: application/json" \
     -d '{
           "question": "Compare Lambda, ECS, and EKS for a microservices architecture.",
           "modelId": "amazon.nova-pro-v1:0",
           "topK": 10,
           "minScore": 0.4
         }'
```

---

### `GET /api/rag/documents`

Lists all documents currently in the knowledge base (metadata only — no chunk vectors).

```bash
curl http://localhost:8080/api/rag/documents
```

```json
[
  {
    "id": "d1a2b3c4-...",
    "title": "AWS Lambda Overview",
    "chunkCount": 6,
    "createdAt": "2025-04-17T09:00:00Z"
  },
  {
    "id": "e5f6a7b8-...",
    "title": "ECS Overview",
    "chunkCount": 4,
    "createdAt": "2025-04-17T09:01:00Z"
  }
]
```

---

### `DELETE /api/rag/documents/{id}`

Removes a single document (and all its chunks) from the knowledge base.

```bash
curl -X DELETE http://localhost:8080/api/rag/documents/d1a2b3c4-...
```

```json
{
  "id": "d1a2b3c4-...",
  "removed": true,
  "message": "Document removed from the knowledge base"
}
```

Returns `404` if the document ID is not found.

---

### `DELETE /api/rag/documents`

Clears the entire knowledge base. Use with care — this is irreversible.

```bash
curl -X DELETE http://localhost:8080/api/rag/documents
```

```json
{
  "cleared": true,
  "documentsRemoved": 3,
  "chunksRemoved": 22,
  "message": "Knowledge base cleared"
}
```

---

### `GET /api/rag/health`

Returns store statistics — use this to verify that ingest succeeded before querying.

```bash
curl http://localhost:8080/api/rag/health
```

```json
{
  "status": "UP",
  "documentCount": 3,
  "chunkCount": 22,
  "timestamp": "2025-04-17T09:05:00Z"
}
```

---

### End-to-end walkthrough

```bash
# 1. Ingest two documents
DOC_IDS=$(curl -s -X POST http://localhost:8080/api/rag/ingest \
  -H "Content-Type: application/json" \
  -d '{
        "documents": [
          {
            "title": "AWS Lambda",
            "content": "AWS Lambda is serverless compute. It runs code triggered by events such as HTTP requests via API Gateway, S3 uploads, DynamoDB streams, or custom events. Lambda automatically scales from zero to thousands of concurrent invocations. Pricing is per 100ms of execution time and number of requests — you pay nothing when your code is not running."
          },
          {
            "title": "Amazon ECS",
            "content": "Amazon Elastic Container Service (ECS) is a fully managed container orchestration service. It supports Docker containers and integrates with AWS Fargate for serverless containers (no EC2 management) or EC2 launch type for full control. ECS is a good fit for long-running services, batch workloads, and microservices that require persistent connections."
          }
        ],
        "chunkSize": 50,
        "chunkOverlap": 10
      }' | jq -r '.documentIds[]')

echo "Ingested document IDs: $DOC_IDS"

# 2. Check the store
curl http://localhost:8080/api/rag/health

# 3. Ask a question
curl -s -X POST http://localhost:8080/api/rag/query \
  -H "Content-Type: application/json" \
  -d '{ "question": "When should I use Lambda vs ECS?", "topK": 4 }' \
  | jq '{answer, sources: [.sources[] | {title, score}]}'

# 4. Clean up
curl -X DELETE http://localhost:8080/api/rag/documents
```

---

## API — Document Analysis

Extracts structured insights from any text in a single Bedrock call —
sentiment, named entities, key phrases, topic classification, and language detection.

> **Model:** Uses the Converse API. Default: `amazon.nova-lite-v1:0`.
> For large documents or high-entity texts, consider `amazon.nova-pro-v1:0`.

### Analysis types

| Type | What it returns |
|------|-----------------|
| `SENTIMENT` | Dominant label (POSITIVE / NEGATIVE / NEUTRAL / MIXED) + per-class confidence scores |
| `ENTITIES` | Named entities with type (PERSON, ORGANIZATION, LOCATION, DATE, MONEY, PRODUCT, EVENT, …) and confidence |
| `KEY_PHRASES` | Up to 15 most important phrases, ranked by relevance score |
| `CLASSIFICATION` | Text category — built-in taxonomy or your own labels |
| `LANGUAGE_DETECTION` | BCP-47 language code, full language name, confidence |

**Built-in classification labels:** TECHNOLOGY, BUSINESS, HEALTH, SCIENCE, POLITICS, SPORTS, ENTERTAINMENT, FINANCE, LEGAL, OTHER

---

### `POST /api/analysis/analyze`

Runs one or more analyses on the provided text.
All five analyses run by default when `analysisTypes` is omitted.

#### Request

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `text` | `string` | Yes | — | Text to analyse (max 100 000 chars) |
| `analysisTypes` | `AnalysisType[]` | No | all 5 | Subset of analyses to run |
| `customLabels` | `string[]` | No | built-in taxonomy | Custom category labels for `CLASSIFICATION` (max 30) |
| `modelId` | `string` | No | config | Override the Bedrock model |

#### Response

```json
{
  "analysisTypes": ["SENTIMENT", "ENTITIES", "KEY_PHRASES", "CLASSIFICATION", "LANGUAGE_DETECTION"],
  "sentiment": {
    "label": "POSITIVE",
    "confidence": 0.91,
    "positiveScore": 0.82,
    "negativeScore": 0.03,
    "neutralScore":  0.12,
    "mixedScore":    0.03
  },
  "entities": [
    { "text": "Apple Inc.", "type": "ORGANIZATION", "confidence": 0.99 },
    { "text": "Tim Cook",   "type": "PERSON",       "confidence": 0.98 },
    { "text": "$89.5B",     "type": "MONEY",        "confidence": 0.97 }
  ],
  "keyPhrases": [
    { "phrase": "record revenue",        "score": 0.95 },
    { "phrase": "strong iPhone 15 sales","score": 0.91 },
    { "phrase": "share buyback",         "score": 0.88 }
  ],
  "classifications": [
    { "label": "FINANCE",    "score": 0.88 },
    { "label": "TECHNOLOGY", "score": 0.72 }
  ],
  "language": {
    "languageCode": "en",
    "languageName": "English",
    "confidence":   0.99
  },
  "modelId": "amazon.nova-lite-v1:0",
  "usage": { "inputTokens": 540, "outputTokens": 210, "totalTokens": 750 },
  "timestamp": "2025-04-17T09:00:00Z"
}
```

Sections not requested are `null` in the response.

#### Examples

**Run all analyses (default):**
```bash
curl -X POST http://localhost:8080/api/analysis/analyze \
     -H "Content-Type: application/json" \
     -d '{
           "text": "Apple Inc. reported record revenue of $89.5B in Q1 2024, beating analyst expectations. CEO Tim Cook cited strong iPhone 15 sales and growth in services revenue. The company also announced a $110B share buyback programme."
         }'
```

**Sentiment only:**
```bash
curl -X POST http://localhost:8080/api/analysis/analyze \
     -H "Content-Type: application/json" \
     -d '{
           "text": "The product crashed immediately after launch. Customers are furious and support queues are overwhelmed.",
           "analysisTypes": ["SENTIMENT"]
         }'
```

**Entities + key phrases only:**
```bash
curl -X POST http://localhost:8080/api/analysis/analyze \
     -H "Content-Type: application/json" \
     -d '{
           "text": "Elon Musk announced that Tesla will open a new Gigafactory in Mexico in 2026, investing $5B.",
           "analysisTypes": ["ENTITIES", "KEY_PHRASES"]
         }'
```

**Custom classification labels (e.g. support ticket triage):**
```bash
curl -X POST http://localhost:8080/api/analysis/analyze \
     -H "Content-Type: application/json" \
     -d '{
           "text": "I cannot log into my account. The password reset email never arrives.",
           "analysisTypes": ["CLASSIFICATION"],
           "customLabels": ["Bug Report", "Feature Request", "Account Issue", "Billing", "General Inquiry"]
         }'
```

**Language detection:**
```bash
curl -X POST http://localhost:8080/api/analysis/analyze \
     -H "Content-Type: application/json" \
     -d '{
           "text": "Bonjour le monde. Ceci est un exemple de texte en français.",
           "analysisTypes": ["LANGUAGE_DETECTION"]
         }'
```

**Analyse a file:**
```bash
curl -X POST http://localhost:8080/api/analysis/analyze \
     -H "Content-Type: application/json" \
     -d "{\"text\": $(jq -Rs . < article.txt)}"
```

**Use a more powerful model for long, entity-dense documents:**
```bash
curl -X POST http://localhost:8080/api/analysis/analyze \
     -H "Content-Type: application/json" \
     -d '{
           "text": "YOUR_LONG_DOCUMENT_HERE",
           "modelId": "amazon.nova-pro-v1:0"
         }'
```

---

### `GET /api/analysis/types`

Returns all available analysis types with descriptions — useful for building a UI picker.

```bash
curl http://localhost:8080/api/analysis/types
```

```json
[
  { "type": "SENTIMENT",         "description": "Sentiment analysis — classifies overall tone and provides per-sentiment confidence scores" },
  { "type": "ENTITIES",          "description": "Named entity recognition — extracts people, organizations, locations, dates, money, products, and events" },
  { "type": "KEY_PHRASES",       "description": "Key phrase extraction — surfaces the most meaningful phrases and topics" },
  { "type": "CLASSIFICATION",    "description": "Text classification — assigns the text to predefined or custom categories" },
  { "type": "LANGUAGE_DETECTION","description": "Language detection — identifies the language and reports a confidence score" }
]
```

---

### `GET /api/analysis/health`

```bash
curl http://localhost:8080/api/analysis/health
# {"status":"UP","service":"document-analysis"}
```

---

## API — Code Generation

Five code-intelligence operations in one service, all powered by the Bedrock Converse API.
The model is prompted to return structured JSON for every operation — no regex scraping of prose.

> **Model:** Default `amazon.nova-lite-v1:0`. Use `amazon.nova-pro-v1:0` for complex
> code, long files, or when higher accuracy is needed.

### Operations at a glance

| Endpoint | What it does |
|----------|-------------|
| `POST /api/code/generate` | Generate code from a natural language description |
| `POST /api/code/explain`  | Explain what code does (brief or detailed) |
| `POST /api/code/review`   | Review for bugs, security flaws, performance issues, and style |
| `POST /api/code/convert`  | Translate code between programming languages |
| `POST /api/code/fix`      | Debug and correct broken code |
| `GET  /api/code/languages` | List supported languages (informational) |
| `GET  /api/code/review/focuses` | List review focus areas with descriptions |
| `GET  /api/code/health`   | Health check |

---

### `POST /api/code/generate`

Generate complete, runnable code from a plain-English description.

#### Request

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `description` | `string` | Yes | What the code should do (max 10 000 chars) |
| `language` | `string` | Yes | Target language (e.g. `"Java"`, `"Python"`, `"Go"`) |
| `framework` | `string` | No | Framework context (e.g. `"Spring Boot"`, `"FastAPI"`) |
| `requirements` | `string[]` | No | Extra constraints — max 20 items |
| `modelId` | `string` | No | Override model |

#### Response

```json
{
  "code": "def binary_search(arr, target):\n    lo, hi = 0, len(arr) - 1\n    while lo <= hi:\n        ...",
  "language": "Python",
  "explanation": "Iterative binary search that returns the index of target or -1 if not found.",
  "dependencies": [],
  "modelId": "amazon.nova-lite-v1:0",
  "usage": { "inputTokens": 120, "outputTokens": 210, "totalTokens": 330 },
  "timestamp": "2025-04-17T09:00:00Z"
}
```

#### Examples

**Minimal:**
```bash
curl -X POST http://localhost:8080/api/code/generate \
     -H "Content-Type: application/json" \
     -d '{
           "description": "Binary search over a sorted integer array, returning the index or -1",
           "language": "Python"
         }'
```

**With framework and requirements:**
```bash
curl -X POST http://localhost:8080/api/code/generate \
     -H "Content-Type: application/json" \
     -d '{
           "description": "REST endpoint to create a new user with email and password",
           "language": "Java",
           "framework": "Spring Boot",
           "requirements": [
             "Validate request body with Bean Validation",
             "Return HTTP 201 with the created user ID",
             "Hash the password with BCrypt",
             "Add Javadoc"
           ]
         }'
```

**Go concurrency pattern:**
```bash
curl -X POST http://localhost:8080/api/code/generate \
     -H "Content-Type: application/json" \
     -d '{
           "description": "Fan-out/fan-in pipeline that fetches URLs concurrently and collects results",
           "language": "Go",
           "requirements": ["Use goroutines and channels", "Handle errors gracefully", "Respect context cancellation"]
         }'
```

---

### `POST /api/code/explain`

Explain what a piece of code does — brief overview or detailed walkthrough.

#### Request

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `code` | `string` | Yes | — | Code to explain (max 100 000 chars) |
| `language` | `string` | No | auto-detect | Programming language hint |
| `detailLevel` | `BRIEF\|DETAILED` | No | `DETAILED` | How deep the explanation should be |
| `modelId` | `string` | No | config | Override model |

#### Response

```json
{
  "language": "Java",
  "explanation": "This method computes Fibonacci numbers using naive recursion...",
  "keyPoints": [
    "Exponential time complexity O(2^n) — recomputes the same sub-problems repeatedly",
    "No memoization — consider dynamic programming for large n",
    "Base cases: n=0 returns 0, n=1 returns 1"
  ],
  "complexity": "MODERATE",
  "modelId": "amazon.nova-lite-v1:0",
  "usage": { "inputTokens": 85, "outputTokens": 175, "totalTokens": 260 },
  "timestamp": "2025-04-17T09:00:00Z"
}
```

#### Examples

**Explain a recursive function:**
```bash
curl -X POST http://localhost:8080/api/code/explain \
     -H "Content-Type: application/json" \
     -d '{
           "code": "int fib(int n) { return n <= 1 ? n : fib(n-1) + fib(n-2); }",
           "language": "Java"
         }'
```

**Brief explanation of a SQL query:**
```bash
curl -X POST http://localhost:8080/api/code/explain \
     -H "Content-Type: application/json" \
     -d '{
           "code": "SELECT u.name, COUNT(o.id) FROM users u LEFT JOIN orders o ON u.id = o.user_id GROUP BY u.id HAVING COUNT(o.id) > 5",
           "language": "SQL",
           "detailLevel": "BRIEF"
         }'
```

---

### `POST /api/code/review`

Review code for bugs, security vulnerabilities, performance issues, and style problems.
Returns issues ordered by severity (CRITICAL first) and an overall quality rating.

#### Request

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `code` | `string` | Yes | — | Code to review (max 100 000 chars) |
| `language` | `string` | No | auto-detect | Language hint |
| `focusAreas` | `ReviewFocus[]` | No | all 5 areas | Limit review scope |
| `modelId` | `string` | No | config | Override model |

**Review focus areas:** `BUGS`, `SECURITY`, `PERFORMANCE`, `STYLE`, `MAINTAINABILITY`

#### Response

```json
{
  "issues": [
    {
      "severity": "CRITICAL",
      "category": "SECURITY",
      "description": "SQL injection — user input concatenated directly into the query string.",
      "lineReference": "line 3",
      "suggestion": "Use PreparedStatement with parameterized queries."
    },
    {
      "severity": "MEDIUM",
      "category": "STYLE",
      "description": "Method name does not follow Java naming conventions.",
      "lineReference": "line 1",
      "suggestion": "Rename to getUserById following camelCase convention."
    }
  ],
  "summary": "Critical SQL injection vulnerability must be fixed before deployment.",
  "overallRating": 3,
  "modelId": "amazon.nova-lite-v1:0",
  "usage": { "inputTokens": 140, "outputTokens": 220, "totalTokens": 360 },
  "timestamp": "2025-04-17T09:00:00Z"
}
```

**Severity levels:** `CRITICAL` → `HIGH` → `MEDIUM` → `LOW` → `INFO`

#### Examples

**Full review:**
```bash
curl -X POST http://localhost:8080/api/code/review \
     -H "Content-Type: application/json" \
     -d '{
           "code": "public String getUser(String id) { return db.query(\"SELECT * FROM users WHERE id = \" + id); }",
           "language": "Java"
         }'
```

**Security-only review:**
```bash
curl -X POST http://localhost:8080/api/code/review \
     -H "Content-Type: application/json" \
     -d '{
           "code": "YOUR_CODE_HERE",
           "focusAreas": ["SECURITY"]
         }'
```

**Performance + bugs only:**
```bash
curl -X POST http://localhost:8080/api/code/review \
     -H "Content-Type: application/json" \
     -d '{
           "code": "YOUR_CODE_HERE",
           "language": "Python",
           "focusAreas": ["PERFORMANCE", "BUGS"]
         }'
```

---

### `POST /api/code/convert`

Translate code from one language to another, producing idiomatic output.

#### Request

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `code` | `string` | Yes | Code to convert (max 100 000 chars) |
| `targetLanguage` | `string` | Yes | Target language (e.g. `"Go"`, `"Rust"`) |
| `sourceLanguage` | `string` | No | Auto-detected when omitted |
| `modelId` | `string` | No | Override model |

#### Response

```json
{
  "convertedCode": "func add(a, b int) int {\n    return a + b\n}",
  "sourceLanguage": "Python",
  "targetLanguage": "Go",
  "notes": [
    "Added explicit static types — Go is statically typed",
    "Removed def keyword — Go uses func",
    "No return type inference — must declare int return type"
  ],
  "modelId": "amazon.nova-lite-v1:0",
  "usage": { "inputTokens": 90, "outputTokens": 130, "totalTokens": 220 },
  "timestamp": "2025-04-17T09:00:00Z"
}
```

#### Examples

**Python → TypeScript:**
```bash
curl -X POST http://localhost:8080/api/code/convert \
     -H "Content-Type: application/json" \
     -d '{
           "code": "def greet(name: str) -> str:\n    return f\"Hello, {name}!\"",
           "sourceLanguage": "Python",
           "targetLanguage": "TypeScript"
         }'
```

**Java → Rust (auto-detect source):**
```bash
curl -X POST http://localhost:8080/api/code/convert \
     -H "Content-Type: application/json" \
     -d '{
           "code": "public int[] twoSum(int[] nums, int target) { ... }",
           "targetLanguage": "Rust"
         }'
```

---

### `POST /api/code/fix`

Debug and correct broken code. Provide the error message for best results.

#### Request

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `code` | `string` | Yes | Buggy code to fix (max 100 000 chars) |
| `language` | `string` | No | Language hint |
| `errorMessage` | `string` | No | Error / exception / wrong behaviour description (max 5 000 chars) |
| `modelId` | `string` | No | Override model |

#### Response

```json
{
  "fixedCode": "public int divide(int a, int b) {\n    if (b == 0) throw new ArithmeticException(\"Division by zero\");\n    return a / b;\n}",
  "language": "Java",
  "explanation": "Added a guard clause to prevent division by zero before executing the operation.",
  "changes": [
    "Added null/zero guard for parameter b (line 2)",
    "Guard throws a descriptive ArithmeticException instead of crashing"
  ],
  "modelId": "amazon.nova-lite-v1:0",
  "usage": { "inputTokens": 95, "outputTokens": 145, "totalTokens": 240 },
  "timestamp": "2025-04-17T09:00:00Z"
}
```

#### Examples

**Fix with error message:**
```bash
curl -X POST http://localhost:8080/api/code/fix \
     -H "Content-Type: application/json" \
     -d '{
           "code": "public int divide(int a, int b) { return a / b; }",
           "language": "Java",
           "errorMessage": "ArithmeticException: / by zero"
         }'
```

**General fix (no specific error):**
```bash
curl -X POST http://localhost:8080/api/code/fix \
     -H "Content-Type: application/json" \
     -d '{
           "code": "def fib(n): return fib(n-1) + fib(n-2)",
           "language": "Python"
         }'
```

**Fix with stack trace:**
```bash
curl -X POST http://localhost:8080/api/code/fix \
     -H "Content-Type: application/json" \
     -d '{
           "code": "YOUR_CODE_HERE",
           "errorMessage": "NullPointerException at MyClass.java:42\n  caused by: field user was null"
         }'
```

---

### `GET /api/code/languages`

Returns a list of supported programming languages (informational — any language string is accepted).

```bash
curl http://localhost:8080/api/code/languages
# ["Bash","C","C++","C#","CSS","Dart","Go","GraphQL",...]
```

### `GET /api/code/review/focuses`

Returns review focus areas with descriptions.

```bash
curl http://localhost:8080/api/code/review/focuses
```

```json
[
  { "focus": "BUGS",            "description": "Logic errors, null-safety issues, off-by-one errors, exception handling" },
  { "focus": "SECURITY",        "description": "SQL/command injection, insecure defaults, sensitive data exposure, auth weaknesses" },
  { "focus": "PERFORMANCE",     "description": "Time/space complexity, unnecessary allocations, blocking I/O, caching" },
  { "focus": "STYLE",           "description": "Naming conventions, formatting, dead code, magic numbers, code duplication" },
  { "focus": "MAINTAINABILITY", "description": "Readability, testability, SOLID principles, coupling, abstraction quality" }
]
```

### `GET /api/code/health`

```bash
curl http://localhost:8080/api/code/health
# {"status":"UP","service":"code-generation"}
```

---

## API — Managed Knowledge Base (RAG)

Connects to an [Amazon Bedrock Knowledge Base](https://docs.aws.amazon.com/bedrock/latest/userguide/knowledge-base.html)
— a fully managed RAG service backed by a vector store (OpenSearch, Aurora, Pinecone, …).
Unlike the DIY RAG pipeline (`/api/rag/*`), **chunking, embedding, and storage are handled
entirely by AWS** — you only need to point S3 at the console and sync.

### DIY RAG vs Managed Knowledge Base

| | DIY RAG (`/api/rag/*`) | Managed KB (`/api/kb/*`) |
|---|---|---|
| Chunking | Word-based, in-memory | Automatic, configurable in console |
| Embedding | Titan Embed via InvokeModel | Managed by AWS (Titan or Cohere) |
| Vector store | `ConcurrentHashMap` (JVM heap) | OpenSearch / Aurora / Pinecone / … |
| Data ingestion | `POST /api/rag/ingest` | S3 sync in AWS Console / SDK |
| Scale | Hundreds of documents (POC) | Millions of documents (production) |
| Multi-turn sessions | Not supported | ✅ Server-side session history |

### Local setup for Managed Knowledge Base

#### Step 1 — Create a Knowledge Base in AWS Console

1. Open **AWS Console → Amazon Bedrock → Knowledge Bases → Create**
2. Enter a name (e.g. `my-bedrock-kb`) and select **Amazon Titan Embed Text V2** as the embedding model
3. Choose or create an S3 bucket as the data source
4. Choose or let Bedrock create an **OpenSearch Serverless** vector store
5. Click **Create Knowledge Base** and wait for it to become `Active`

#### Step 2 — Upload documents and sync

1. Upload PDF, TXT, DOCX, or HTML files to your S3 bucket
2. In the Knowledge Base detail page, open **Data sources → Sync**
3. Wait for the sync status to show `Ready`

#### Step 3 — Copy the Knowledge Base ID

In the Knowledge Base detail page, copy the **Knowledge Base ID** (e.g. `ABCDEF1234`).

#### Step 4 — Add `KB_ID` to VS Code launch config

Open [`.vscode/launch.json`](.vscode/launch.json) and add the KB ID to the env block:

```json
"env": {
    "AWS_ACCESS_KEY_ID":     "YOUR_ACCESS_KEY",
    "AWS_SECRET_ACCESS_KEY": "YOUR_SECRET_KEY",
    "AWS_REGION":            "us-east-1",
    "KB_ID":                 "ABCDEF1234"
}
```

#### Verify setup

```bash
curl http://localhost:8080/api/kb/health
# {"status":"UP","service":"knowledge-base","knowledgeBaseId":"ABCDEF1234",...}
```

If `KB_ID` is missing the status will be `UNCONFIGURED` and queries will return `400`.

---

### `POST /api/kb/query`

Retrieves relevant chunks from the Knowledge Base **and** generates a grounded answer —
all in a single Bedrock API call (`RetrieveAndGenerate`).

Supports **multi-turn sessions**: pass the `sessionId` from the previous response to
continue the conversation. Bedrock maintains session history server-side (~1 hour TTL).

#### Request

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `question` | `string` | Yes | — | Question to answer (max 10 000 chars) |
| `topK` | `integer` | No | `5` | Max chunks to retrieve |
| `sessionId` | `string` | No | — | Resume a previous session for multi-turn |
| `knowledgeBaseId` | `string` | No | config `KB_ID` | Override KB for this request |
| `modelId` | `string` | No | `amazon.nova-lite-v1:0` | Override generation model |

#### Response

```json
{
  "question":        "What is AWS Lambda?",
  "answer":          "AWS Lambda is a serverless compute service that lets you run code without provisioning servers...",
  "sessionId":       "sess-abc123",
  "citations": [
    {
      "content":      "Lambda is an event-driven, serverless compute platform...",
      "sourceUri":    "s3://my-bucket/docs/lambda.pdf",
      "locationType": "S3",
      "metadata":     { "x-amz-bedrock-kb-source-uri": "s3://my-bucket/docs/lambda.pdf" }
    }
  ],
  "citationCount":   1,
  "knowledgeBaseId": "ABCDEF1234",
  "modelId":         "amazon.nova-lite-v1:0",
  "timestamp":       "2025-04-17T09:00:00Z"
}
```

#### Examples

**Simple question:**
```bash
curl -X POST http://localhost:8080/api/kb/query \
     -H "Content-Type: application/json" \
     -d '{ "question": "What is AWS Lambda?" }'
```

**Multi-turn conversation:**
```bash
# Turn 1 — start a session
RESP=$(curl -s -X POST http://localhost:8080/api/kb/query \
  -H "Content-Type: application/json" \
  -d '{ "question": "What is AWS Lambda?" }')

SESSION=$(echo $RESP | jq -r .sessionId)
echo "Session: $SESSION"

# Turn 2 — follow-up using the same session
curl -X POST http://localhost:8080/api/kb/query \
     -H "Content-Type: application/json" \
     -d "{
           \"question\": \"How does Lambda pricing work?\",
           \"sessionId\": \"$SESSION\"
         }"
```

**With retrieval tuning and model override:**
```bash
curl -X POST http://localhost:8080/api/kb/query \
     -H "Content-Type: application/json" \
     -d '{
           "question": "Explain Lambda cold start mitigation strategies.",
           "topK": 8,
           "modelId": "amazon.nova-pro-v1:0"
         }'
```

---

### `POST /api/kb/retrieve`

Retrieves relevant chunks from the Knowledge Base **without** generating an answer.
Returns raw chunks with relevance scores — useful for debugging, custom re-ranking,
or feeding into a separate prompt (`POST /api/chat`).

#### Request

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `query` | `string` | Yes | — | Search query (max 10 000 chars) |
| `topK` | `integer` | No | `5` | Max chunks to return |
| `knowledgeBaseId` | `string` | No | config `KB_ID` | Override KB for this request |

#### Response

```json
{
  "query": "Lambda cold start mitigation",
  "chunks": [
    {
      "content":      "Lambda cold starts can be reduced by keeping functions warm...",
      "score":        0.8734,
      "sourceUri":    "s3://my-bucket/docs/lambda-perf.pdf",
      "locationType": "S3",
      "metadata":     { "x-amz-bedrock-kb-source-uri": "s3://my-bucket/docs/lambda-perf.pdf" }
    },
    {
      "content":      "Provisioned concurrency eliminates cold starts entirely...",
      "score":        0.8421,
      "sourceUri":    "s3://my-bucket/docs/lambda-perf.pdf",
      "locationType": "S3",
      "metadata":     {}
    }
  ],
  "totalChunks":     2,
  "knowledgeBaseId": "ABCDEF1234",
  "timestamp":       "2025-04-17T09:00:00Z"
}
```

#### Examples

**Basic retrieval:**
```bash
curl -X POST http://localhost:8080/api/kb/retrieve \
     -H "Content-Type: application/json" \
     -d '{ "query": "Lambda cold start mitigation", "topK": 5 }'
```

**Retrieve then generate with full control:**
```bash
# 1. Get the relevant chunks
CHUNKS=$(curl -s -X POST http://localhost:8080/api/kb/retrieve \
  -H "Content-Type: application/json" \
  -d '{ "query": "Lambda pricing model" }' \
  | jq -r '[.chunks[].content] | join("\n\n")')

# 2. Feed chunks into the Chat endpoint with a custom prompt
curl -X POST http://localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d "{
           \"message\": \"Based on the following context, explain Lambda pricing:\\n\\n$CHUNKS\",
           \"systemPrompt\": \"Answer only from the provided context. Be concise.\"
         }"
```

---

### `GET /api/kb/health`

Returns the Knowledge Base configuration status.
Use this to verify the `KB_ID` is set correctly before making queries.

```bash
curl http://localhost:8080/api/kb/health
```

**When configured:**
```json
{
  "status":          "UP",
  "service":         "knowledge-base",
  "knowledgeBaseId": "ABCDEF1234",
  "modelId":         "amazon.nova-lite-v1:0",
  "defaultTopK":     5
}
```

**When `KB_ID` is not set:**
```json
{
  "status":          "UNCONFIGURED",
  "service":         "knowledge-base",
  "knowledgeBaseId": "(not set — add KB_ID to launch.json)",
  "modelId":         "amazon.nova-lite-v1:0",
  "defaultTopK":     5
}
```

---

## API — Agent Chat (Tool / Function Calling)

The Agent API drives a multi-step **agentic loop** using the Bedrock Converse API's
native Tool Use feature.  The model can invoke built-in tools one or more times
before producing its final answer — no orchestration framework required.

### How the loop works

```
User message
     │
     ▼
  Bedrock Converse  ──(StopReason: TOOL_USE)──▶  Execute tools locally
     ▲                                                    │
     └──────────── ToolResult message ◀──────────────────┘
     │
  (StopReason: END_TURN)
     │
     ▼
  Final answer returned to caller
```

Maximum 10 iterations per request (guard against runaway loops).

### Built-in tools

| Tool | Operations |
|------|-----------|
| `calculator` | `add`, `subtract`, `multiply`, `divide`, `power`, `sqrt`, `modulo` |
| `get_current_time` | Current date/time with optional timezone (IANA) and format (`iso8601`, `date_only`, `time_only`, `human_readable`) |
| `string_utils` | `uppercase`, `lowercase`, `reverse`, `word_count`, `char_count`, `trim` |
| `unit_converter` | Temperature (°C / °F / K), Length (m / ft / in / km / mi), Weight (kg / lb / g / oz) |

---

### `POST /api/agent/chat`

Run the agentic loop and receive the model's final answer together with a
complete log of every tool invocation.

#### Request

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `message` | `string` | Yes | User's question or task (max 100 000 chars) |
| `modelId` | `string` | No | Override the default model for this request |
| `systemPrompt` | `string` | No | Override the default agent system prompt |
| `conversationHistory` | `ChatMessage[]` | No | Prior turns for multi-turn agent sessions |
| `enabledTools` | `string[]` | No | Restrict to a subset of tools (omit to enable all) |

#### Response

```json
{
  "reply":      "1234 × 5678 = 7,006,652.",
  "modelId":    "amazon.nova-lite-v1:0",
  "iterations": 2,
  "toolCalls": [
    {
      "toolName": "calculator",
      "input":    { "operation": "multiply", "a": 1234, "b": 5678 },
      "output":   "7006652"
    }
  ],
  "usage": { "inputTokens": 312, "outputTokens": 48, "totalTokens": 360 },
  "timestamp": "2025-04-18T00:00:00Z"
}
```

- `iterations` — number of Bedrock round-trips (1 means no tools were called)
- `toolCalls` — ordered list of every tool the agent invoked, with inputs and outputs
- `usage` — token counts aggregated across **all** iterations

#### Examples

**Math question (calculator tool):**
```bash
curl -X POST http://localhost:8080/api/agent/chat \
     -H "Content-Type: application/json" \
     -d '{ "message": "What is 1234 multiplied by 5678?" }'
```

**Unit conversion:**
```bash
curl -X POST http://localhost:8080/api/agent/chat \
     -H "Content-Type: application/json" \
     -d '{ "message": "Convert 100 pounds to kilograms." }'
```

**Current time in a timezone:**
```bash
curl -X POST http://localhost:8080/api/agent/chat \
     -H "Content-Type: application/json" \
     -d '{ "message": "What time is it right now in Tokyo?" }'
```

**String manipulation:**
```bash
curl -X POST http://localhost:8080/api/agent/chat \
     -H "Content-Type: application/json" \
     -d '{ "message": "Reverse the string \"Hello, World!\" and count its words." }'
```

**Restrict available tools:**
```bash
curl -X POST http://localhost:8080/api/agent/chat \
     -H "Content-Type: application/json" \
     -d '{
           "message": "What is 2 to the power of 10?",
           "enabledTools": ["calculator"]
         }'
```

**Multi-turn agent session:**
```bash
# Turn 1
RESP=$(curl -s -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{ "message": "What is 100 Celsius in Fahrenheit?" }')

echo $RESP | jq .reply

# Turn 2 — carry history forward
curl -X POST http://localhost:8080/api/agent/chat \
     -H "Content-Type: application/json" \
     -d "{
           \"message\": \"And what is that in Kelvin?\",
           \"conversationHistory\": $(echo $RESP | jq '[{role:\"user\",content:\"What is 100 Celsius in Fahrenheit?\"},{role:\"assistant\",content:.reply}]')
         }"
```

---

### `GET /api/agent/tools`

List all built-in tools and their descriptions.

```bash
curl http://localhost:8080/api/agent/tools
```

```json
{
  "calculator":      "Arithmetic and math operations: add, subtract, multiply, divide, power, sqrt, modulo",
  "get_current_time": "Get the current date/time, optionally in a specific timezone and format",
  "string_utils":    "Text transformations: uppercase, lowercase, reverse, word_count, char_count, trim",
  "unit_converter":  "Unit conversions: temperature (°C/°F/K), length (m/ft/in/km/mi), weight (kg/lb/g/oz)"
}
```

---

### `GET /api/agent/health`

Returns `200 OK` — `Agent service is running`

---

## API — Image Generation

Generate images from text prompts or create variations of existing images using
Amazon Bedrock's image models via the `InvokeModel` API.

### Supported models

| Model | Model ID | Notes |
|-------|----------|-------|
| Titan Image Generator G1 V2 **(default)** | `amazon.titan-image-generator-v2:0` | TEXT_IMAGE + IMAGE_VARIATION; no form required |
| Titan Image Generator G1 V1 | `amazon.titan-image-generator-v1` | Previous generation |
| Stability AI SDXL | `stability.stable-diffusion-xl-v1` | TEXT_IMAGE only; requires Stability AI form in AWS Console |

### Response format

All generated images are returned as **base64-encoded PNG strings** in the `images` array.

```bash
# Save the first generated image to a file
curl -s -X POST http://localhost:8080/api/images/generate \
     -H "Content-Type: application/json" \
     -d '{ "prompt": "A photorealistic mountain sunset" }' \
  | jq -r '.images[0]' | base64 -d > output.png
```

---

### `POST /api/images/generate`

Generate one or more images from a text prompt.

#### Request

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `prompt` | `string` | Yes | Text description of the image (max 512 chars) |
| `negativePrompt` | `string` | No | Concepts to exclude (e.g. `"blurry, watermark"`) |
| `width` | `integer` | No | Width in pixels (64–2048, default 1024) |
| `height` | `integer` | No | Height in pixels (64–2048, default 1024) |
| `numberOfImages` | `integer` | No | Images to generate in one call (1–5, default 1) |
| `cfgScale` | `number` | No | Prompt-adherence strength (1.1–10.0 Titan, default 8.0) |
| `seed` | `long` | No | Seed for reproducibility (omit for random) |
| `quality` | `string` | No | `"standard"` (faster) or `"premium"` (Titan V2 only) |
| `modelId` | `string` | No | Override the default image model |

#### Response

```json
{
  "images": ["iVBORw0KGgoAAAANSUhEUgAA..."],
  "imagesGenerated": 1,
  "modelId": "amazon.titan-image-generator-v2:0",
  "timestamp": "2025-04-18T00:00:00Z"
}
```

#### Examples

**Minimal — single image:**
```bash
curl -X POST http://localhost:8080/api/images/generate \
     -H "Content-Type: application/json" \
     -d '{ "prompt": "A photorealistic mountain sunset, golden hour, 8K" }' \
  | jq -r '.images[0]' | base64 -d > sunset.png
```

**Multiple images with negative prompt:**
```bash
curl -X POST http://localhost:8080/api/images/generate \
     -H "Content-Type: application/json" \
     -d '{
           "prompt":         "A serene Japanese zen garden with koi pond",
           "negativePrompt": "people, animals, cartoon, blurry, watermark",
           "width":          1024,
           "height":         1024,
           "numberOfImages": 3,
           "cfgScale":       9.0,
           "quality":        "premium"
         }' \
  | jq -r '.images[]' | while IFS= read -r img; do
      echo "$img" | base64 -d > "garden_$(date +%s%N).png"
    done
```

**Reproducible image with seed:**
```bash
curl -X POST http://localhost:8080/api/images/generate \
     -H "Content-Type: application/json" \
     -d '{
           "prompt": "A futuristic city at night, neon lights",
           "seed":   42
         }' \
  | jq -r '.images[0]' | base64 -d > city.png
```

**Use Stability AI SDXL:**
```bash
curl -X POST http://localhost:8080/api/images/generate \
     -H "Content-Type: application/json" \
     -d '{
           "prompt":   "Epic fantasy landscape, oil painting style",
           "modelId":  "stability.stable-diffusion-xl-v1",
           "width":    1024,
           "height":   1024,
           "cfgScale": 10.0
         }' \
  | jq -r '.images[0]' | base64 -d > fantasy.png
```

---

### `POST /api/images/variation`

Generate one or more variations of an existing image.
The input image must be base64-encoded (PNG or JPEG).
Supported by Titan Image Generator models only.

#### Request

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `inputImageBase64` | `string` | Yes | Base64-encoded source image (no `data:image/...` prefix) |
| `prompt` | `string` | No | Text guidance for how the variation should differ |
| `negativePrompt` | `string` | No | Concepts to exclude from the variation |
| `similarityStrength` | `number` | No | How closely to match the input (0.2–1.0, default 0.7) |
| `numberOfImages` | `integer` | No | Number of variations (1–5, default 1) |
| `cfgScale` | `number` | No | Prompt-adherence scale |
| `seed` | `long` | No | Seed for reproducibility |
| `quality` | `string` | No | `"standard"` or `"premium"` (Titan V2 only) |
| `modelId` | `string` | No | Override the default image model |

#### Example

```bash
# Read an existing image as base64, then generate a variation
INPUT_B64=$(base64 -w 0 input.png)

curl -X POST http://localhost:8080/api/images/variation \
     -H "Content-Type: application/json" \
     -d "{
           \"inputImageBase64\":  \"$INPUT_B64\",
           \"prompt\":            \"same composition but at night with moonlight\",
           \"similarityStrength\": 0.6
         }" \
  | jq -r '.images[0]' | base64 -d > variation.png
```

---

### `GET /api/images/models`

List all supported image models and their capabilities.

```bash
curl http://localhost:8080/api/images/models
```

---

### `GET /api/images/health`

Returns `200 OK` — `Image generation service is running`

---

## Project structure

```
aws-bedrock-poc/
├── src/
│   ├── main/
│   │   ├── java/com/example/bedrock/
│   │   │   ├── BedrockPocApplication.java          # Entry point
│   │   │   ├── config/
│   │   │   │   ├── BedrockConfig.java              # AWS SDK client beans + thread pool
│   │   │   │   └── BedrockProperties.java          # Typed config (application.yml)
│   │   │   ├── controller/
│   │   │   │   ├── ChatController.java             # /api/chat  &  /api/chat/stream
│   │   │   │   ├── SummarizationController.java    # /api/summarize
│   │   │   │   ├── EmbeddingController.java        # /api/embeddings/*
│   │   │   │   ├── RagController.java              # /api/rag/*
│   │   │   │   ├── DocumentAnalysisController.java # /api/analysis/*
│   │   │   │   ├── CodeGenerationController.java   # /api/code/*
│   │   │   │   ├── KnowledgeBaseController.java    # /api/kb/*
│   │   │   │   ├── AgentController.java            # /api/agent/*
│   │   │   │   └── ImageGenerationController.java  # /api/images/*
│   │   │   ├── service/
│   │   │   │   ├── ChatService.java                # Blocking Converse API
│   │   │   │   ├── StreamingChatService.java       # ConverseStream + SseEmitter
│   │   │   │   ├── SummarizationService.java       # Style-guided summarization
│   │   │   │   ├── EmbeddingService.java           # InvokeModel + cosine similarity
│   │   │   │   ├── RagService.java                 # RAG pipeline: chunk → embed → retrieve → generate
│   │   │   │   ├── DocumentStore.java              # Thread-safe in-memory vector store
│   │   │   │   ├── DocumentAnalysisService.java    # Sentiment, NER, key phrases, classification, language
│   │   │   │   ├── CodeGenerationService.java      # Generate, explain, review, convert, fix
│   │   │   │   ├── KnowledgeBaseService.java       # Managed KB: RetrieveAndGenerate + Retrieve
│   │   │   │   ├── AgentService.java               # Agentic loop: tool definitions, dispatch, result handling
│   │   │   │   └── ImageGenerationService.java     # InvokeModel: Titan + Stability AI image generation
│   │   │   ├── model/
│   │   │   │   ├── ChatMessage.java                # role + content pair
│   │   │   │   ├── ChatRequest.java                # Chat POST body
│   │   │   │   ├── ChatResponse.java               # Chat response
│   │   │   │   ├── StreamToken.java                # SSE event payload
│   │   │   │   ├── SummarizeRequest.java           # Summarize POST body + SummaryStyle enum
│   │   │   │   ├── SummarizeResponse.java          # Summarize response + compression metrics
│   │   │   │   ├── EmbedRequest.java               # Embed POST body
│   │   │   │   ├── EmbedResponse.java              # Embedding vector + token count
│   │   │   │   ├── SimilarityRequest.java          # Two texts to compare
│   │   │   │   ├── SimilarityResponse.java         # Cosine score + interpretation
│   │   │   │   ├── SemanticSearchRequest.java      # Query + document corpus
│   │   │   │   ├── SemanticSearchResponse.java     # Ranked results
│   │   │   │   ├── IngestRequest.java              # RAG: batch document ingest
│   │   │   │   ├── IngestResponse.java             # RAG: ingest result with doc IDs
│   │   │   │   ├── RagQueryRequest.java            # RAG: question + retrieval params
│   │   │   │   ├── RagQueryResponse.java           # RAG: answer + sources + token usage
│   │   │   │   ├── AnalysisType.java               # Enum: SENTIMENT, ENTITIES, KEY_PHRASES, CLASSIFICATION, LANGUAGE_DETECTION
│   │   │   │   ├── AnalysisRequest.java            # Document analysis request
│   │   │   │   ├── AnalysisResponse.java           # Analysis results + inner result types
│   │   │   │   ├── DetailLevel.java                # Enum: BRIEF | DETAILED (code explain)
│   │   │   │   ├── ReviewFocus.java                # Enum: BUGS, SECURITY, PERFORMANCE, STYLE, MAINTAINABILITY
│   │   │   │   ├── CodeGenerateRequest.java        # Code generation request
│   │   │   │   ├── CodeGenerateResponse.java       # Generated code + dependencies
│   │   │   │   ├── CodeExplainRequest.java         # Code explanation request
│   │   │   │   ├── CodeExplainResponse.java        # Explanation + key points + complexity
│   │   │   │   ├── CodeReviewRequest.java          # Code review request
│   │   │   │   ├── CodeReviewResponse.java         # Issues list + rating (with ReviewIssue inner class)
│   │   │   │   ├── CodeConvertRequest.java         # Code conversion request
│   │   │   │   ├── CodeConvertResponse.java        # Converted code + notes
│   │   │   │   ├── CodeFixRequest.java             # Code fix request
│   │   │   │   ├── CodeFixResponse.java            # Fixed code + changes list
│   │   │   │   ├── KbQueryRequest.java             # Managed KB: question + session + topK
│   │   │   │   ├── KbQueryResponse.java            # Managed KB: answer + citations (with Citation inner class)
│   │   │   │   ├── KbRetrieveRequest.java          # Managed KB: retrieve-only request
│   │   │   │   ├── KbRetrieveResponse.java         # Managed KB: chunks + scores (with RetrievedChunk inner class)
│   │   │   │   ├── AgentRequest.java               # Agent POST body (message, tools, history)
│   │   │   │   ├── AgentResponse.java              # Agent response (reply, toolCalls, iterations, usage)
│   │   │   │   ├── ToolCallRecord.java             # Single tool invocation log (name, input, output)
│   │   │   │   ├── ImageGenerateRequest.java       # Image generation POST body
│   │   │   │   ├── ImageVariationRequest.java      # Image variation POST body
│   │   │   │   └── ImageGenerateResponse.java      # Base64 images + metadata
│   │   │   └── exception/
│   │   │       ├── BedrockException.java           # Bedrock API errors
│   │   │       └── GlobalExceptionHandler.java     # RFC 7807 error responses
│   │   └── resources/
│   │       └── application.yml                     # All configuration
│   └── test/
│       └── java/com/example/bedrock/
│           ├── ChatServiceTest.java                # 4 tests — Chat (mocked)
│           ├── SummarizationServiceTest.java       # 10 tests — Summarization (mocked)
│           ├── EmbeddingServiceTest.java           # 8 tests — Embeddings (mocked)
│           ├── RagServiceTest.java                 # 12 tests — RAG (mocked)
│           ├── DocumentAnalysisServiceTest.java    # 12 tests — Document Analysis (mocked)
│           ├── CodeGenerationServiceTest.java      # 16 tests — Code Generation (mocked)
│           ├── KnowledgeBaseServiceTest.java       # 11 tests — Managed KB (mocked)
│           ├── AgentServiceTest.java               # 26 tests — Agent tool loop (mocked)
│           └── ImageGenerationServiceTest.java     # 12 tests — Image generation (mocked)
├── .vscode/
│   └── launch.json                                 # AWS credentials (gitignored)
├── .gitignore
├── pom.xml
└── README.md
```

---

## Running tests

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 mvn test
```

All tests mock the Bedrock client — no AWS credentials or network access required.

```
Tests run: 111, Failures: 0, Errors: 0, Skipped: 0
```

---

## Supported Bedrock models

> **Cross-region inference prefix:** Claude 3.5+ models require the `us.` prefix so
> Bedrock can route to any US region with spare capacity.

**Amazon Nova** — no Anthropic use-case form required:

| Model | Model ID | Notes |
|-------|----------|-------|
| Nova Lite **(default)** | `amazon.nova-lite-v1:0` | Balanced speed & quality |
| Nova Micro | `amazon.nova-micro-v1:0` | Fastest, text-only |
| Nova Pro | `amazon.nova-pro-v1:0` | Highest quality |

**Anthropic Claude** — requires the [Anthropic use-case form](https://console.aws.amazon.com/bedrock/home#/modelaccess):

| Model | Model ID |
|-------|----------|
| Claude 3.7 Sonnet | `us.anthropic.claude-3-7-sonnet-20250219-v1:0` |
| Claude 3.5 Haiku | `us.anthropic.claude-3-5-haiku-20241022-v1:0` |

> Enable model access: **AWS Console → Bedrock → Model access → Manage model access**

---

## Roadmap

| # | Feature | Status |
|---|---------|--------|
| 1 | Chat API (blocking) | ✅ Done |
| 2 | Streaming Chat (SSE) | ✅ Done |
| 3 | Text Summarization (5 styles) | ✅ Done |
| 4 | Embeddings + Semantic Search | ✅ Done |
| 5 | RAG — DIY in-memory pipeline | ✅ Done |
| 6 | Document Analysis (sentiment, NER, key phrases, classification, language) | ✅ Done |
| 7 | Code Generation (generate, explain, review, convert, fix) | ✅ Done |
| 8 | RAG with Bedrock Knowledge Bases (managed) | ✅ Done |
| 9 | Agents with tool / function calling | ✅ Done |
| 10 | Image Generation | ✅ Done |
| 11 | Prompt Flows | Planned |
