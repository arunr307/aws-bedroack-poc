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
10. [Project structure](#project-structure)
11. [Running tests](#running-tests)
12. [Supported Bedrock models](#supported-bedrock-models)
13. [Roadmap](#roadmap)

---

## Architecture overview

```
Client (curl / Postman / UI)
              │
              ▼
┌──────────────────────────────────────────────────────────┐
│                  Spring Boot  :8080                      │
│                                                          │
│  ChatController        ──▶  ChatService                  │
│  ChatController        ──▶  StreamingChatService         │
│  SummarizationController──▶  SummarizationService        │
│  EmbeddingController   ──▶  EmbeddingService             │
│  RagController         ──▶  RagService                   │
│                              │   └──▶ EmbeddingService   │
│                              └──▶ DocumentStore          │
│                                    │                     │
│                     BedrockRuntimeClient  (sync)         │
│                     BedrockRuntimeAsyncClient (streaming)│
└──────────────────────────────────────────────────────────┘
                                │
              ┌─────────────────┼──────────────────┐
              ▼                 ▼                  ▼
       Converse API       ConverseStream      InvokeModel API
    (Chat, Summarize,     (Stream Chat)      (Embeddings, RAG)
       RAG generation)
              │                 │                  │
              └─────────────────┼──────────────────┘
                                ▼
            Foundation Model (Nova Lite / Titan Embed / Claude …)
```

| API | Bedrock call | Used by |
|-----|-------------|---------|
| Converse API | `client.converse()` | Chat, Summarization, RAG generation |
| ConverseStream API | `asyncClient.converseStream()` | Streaming Chat |
| InvokeModel API | `client.invokeModel()` | Embeddings, RAG chunk embedding |

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
│   │   │   │   └── RagController.java              # /api/rag/*
│   │   │   ├── service/
│   │   │   │   ├── ChatService.java                # Blocking Converse API
│   │   │   │   ├── StreamingChatService.java       # ConverseStream + SseEmitter
│   │   │   │   ├── SummarizationService.java       # Style-guided summarization
│   │   │   │   ├── EmbeddingService.java           # InvokeModel + cosine similarity
│   │   │   │   ├── RagService.java                 # RAG pipeline: chunk → embed → retrieve → generate
│   │   │   │   └── DocumentStore.java              # Thread-safe in-memory vector store
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
│   │   │   │   └── RagQueryResponse.java           # RAG: answer + sources + token usage
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
│           └── RagServiceTest.java                 # 11 tests — RAG (mocked)
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
Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
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
| 6 | Document Analysis (entities, sentiment, classification) | Planned |
| 7 | Code Generation | Planned |
| 8 | RAG with Bedrock Knowledge Bases (managed) | Planned |
| 9 | Agents with tool / function calling | Planned |
| 10 | Image Generation | Planned |
| 11 | Prompt Flows | Planned |
