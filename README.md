# RAG Application

**Production-ready Retrieval-Augmented Generation (RAG) API** built with Spring Boot 3.x, Java 21, Spring AI, a local Ollama LLM (llama3.2:3b), PostgreSQL + pgvector, and Redis.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                         RAG Application                              │
│                                                                      │
│  REST Controllers  →  Services  →  AI Layer  →  Repositories        │
│  ─────────────────────────────────────────────────────────────────  │
│  AuthController        AuthService     EmbeddingService   UserRepo   │
│  DocumentController    DocumentService  RetrieverService   DocRepo   │
│  ChatController        IndexingService  PromptBuilder      ChunkRepo │
│  HealthController      ChatService      LlmClient          ConvRepo  │
│                        AuditService                        MsgRepo   │
│                                                                      │
│  Security: JWT Filter → UserDetailsService → BCrypt                 │
│  Chunking:  FixedSize | Recursive | Semantic                        │
│  Parsing:   PDF | DOCX | TXT | Markdown | HTML  (Tika fallback)     │
│  Cache:     Redis  (embeddings, search, docs, conversations)         │
│  Metrics:   Micrometer → Prometheus → Grafana                       │
└──────────────────────────────────────────────────────────────────────┘
```

### RAG Retrieval Flow

```
User Question
     │
     ▼
Generate Embedding  (Ollama nomic-embed-text)
     │
     ▼
Similarity Search  ──→  Hybrid Search (Vector + Keyword BM25)
     │                  if hybrid_search_enabled=true
     ▼
Top-K Chunks  ──→  Context Compression (trim to max_context_tokens)
     │
     ▼
Build Prompt  (system + history + context + question)
     │
     ▼
Call Ollama LLM (llama3.2:3b)  (streaming or synchronous)
     │
     ▼
Persist Message  →  Return Answer + Sources + Scores
```

---

## Project Structure

```
rag-application/
├── src/main/java/com/ragapp/
│   ├── RagApplication.java           Main entry point
│   ├── controller/                   REST endpoints
│   │   ├── AuthController.java       POST /auth/*
│   │   ├── ChatController.java       POST /chat, /search, GET /conversations
│   │   ├── DocumentController.java   POST /documents/upload, /index, GET/DELETE
│   │   └── HealthController.java     GET /health
│   ├── service/                      Business logic
│   │   ├── AuthService.java
│   │   ├── ChatService.java          RAG orchestration
│   │   ├── DocumentService.java
│   │   ├── IndexingService.java      Parse→Chunk→Embed→Store pipeline
│   │   └── AuditService.java
│   ├── ai/
│   │   ├── embedding/EmbeddingService.java  Ollama embeddings + cache
│   │   ├── retriever/RetrieverService.java  Vector + hybrid search
│   │   ├── prompt/PromptBuilder.java        RAG prompt assembly
│   │   └── llm/LlmClient.java              Ollama chat sync + streaming
│   ├── chunking/
│   │   ├── ChunkingService.java      Strategy router
│   │   ├── FixedSizeChunker.java     Fixed window
│   │   ├── RecursiveChunker.java     Boundary-aware (recommended)
│   │   └── SemanticChunker.java      Sentence-boundary
│   ├── parser/DocumentParserService.java    PDF/DOCX/MD/HTML/TXT
│   ├── entity/                       JPA entities
│   │   ├── User.java
│   │   ├── Document.java
│   │   ├── Chunk.java                vector(768) column
│   │   ├── Conversation.java
│   │   ├── Message.java
│   │   └── AuditLog.java
│   ├── repository/                   Spring Data JPA
│   ├── dto/                          Java Records (immutable)
│   ├── security/                     JWT + Spring Security
│   ├── config/                       AppProperties, SecurityConfig, etc.
│   ├── exception/                    GlobalExceptionHandler + typed exceptions
│   ├── mapper/                       MapStruct mappers
│   └── util/                         SecurityUtils
├── src/main/resources/
│   ├── application.yml               Main config
│   ├── application-test.yml          Test overrides
│   └── db/migration/                 Flyway migrations (V1 schema, V2 Ollama embedding dims)
├── src/test/java/com/ragapp/
│   ├── unit/                         Pure unit tests (no Spring)
│   └── integration/                  Spring + Testcontainers
├── docker/
│   ├── prometheus.yml
│   └── grafana/provisioning/
├── Dockerfile                        Multi-stage build
├── docker-compose.yml                Full stack
└── pom.xml
```

---

## Quick Start

### Prerequisites

- Java 21
- Docker + Docker Compose
- [Ollama](https://ollama.com) — either running locally, or via the `ollama` service in
  `docker-compose.yml` (no API key required; it's a local model runtime)

### 1. Clone and configure

```bash
cp .env.example .env
# .env already defaults to a local Ollama instance — adjust OLLAMA_BASE_URL,
# AI_CHAT_MODEL, or AI_EMBEDDING_MODEL only if you need different values.
```

### 2. Run with Docker Compose

```bash
docker-compose up -d

# Pull the required models into the ollama container (first run only)
docker exec rag-ollama ollama pull llama3.2:3b
docker exec rag-ollama ollama pull nomic-embed-text
```

Services:
| Service      | URL                          |
|-------------|------------------------------|
| API          | http://localhost:8080/api    |
| Swagger UI   | http://localhost:8080/api/swagger-ui.html |
| Prometheus   | http://localhost:9090        |
| Grafana      | http://localhost:3000        |
| Ollama       | http://localhost:11434       |

### 3. Run locally (development)

```bash
# Start dependencies only (Postgres, Redis, Ollama)
docker-compose up -d postgres redis ollama
docker exec rag-ollama ollama pull llama3.2:3b
docker exec rag-ollama ollama pull nomic-embed-text

# Run Spring Boot — OLLAMA_BASE_URL defaults to http://localhost:11434
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

> If Ollama is running natively on your host instead of in Docker, just make sure
> `ollama serve` is running and `OLLAMA_BASE_URL` in `.env` points at it
> (default `http://localhost:11434`).

---

## API Reference

### Authentication

#### Register
```http
POST /api/auth/register
Content-Type: application/json

{
  "username": "alice",
  "email": "alice@example.com",
  "password": "SecurePass123!",
  "fullName": "Alice Smith"
}
```

#### Login
```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "alice",
  "password": "SecurePass123!"
}

Response:
{
  "accessToken": "eyJhbGci...",
  "refreshToken": "eyJhbGci...",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "user": { "id": "...", "username": "alice", "roles": ["USER"] }
}
```

### Documents

#### Upload a document
```http
POST /api/documents/upload?index=true
Authorization: Bearer <token>
Content-Type: multipart/form-data

file=@/path/to/document.pdf
```

#### Index a document
```http
POST /api/documents/index
Authorization: Bearer <token>
Content-Type: application/json

{
  "documentId": "550e8400-e29b-41d4-a716-446655440000",
  "chunkingStrategy": "recursive",
  "chunkSize": 1000,
  "chunkOverlap": 200
}
```

#### List documents
```http
GET /api/documents?page=0&size=20
Authorization: Bearer <token>
```

#### Delete a document
```http
DELETE /api/documents/{id}
Authorization: Bearer <token>
```

### Chat

#### Ask a question
```http
POST /api/chat
Authorization: Bearer <token>
Content-Type: application/json

{
  "question": "What are the main findings of the report?",
  "conversationId": null,
  "topK": 5,
  "minSimilarity": 0.7
}

Response:
{
  "conversationId": "...",
  "messageId": "...",
  "answer": "The report highlights three main findings...",
  "sources": [
    {
      "chunkId": "...",
      "documentName": "annual-report.pdf",
      "chunkText": "...",
      "similarityScore": 0.92,
      "pageNumber": 5
    }
  ],
  "sourcesUsed": 3,
  "retrievalLatencyMs": 120,
  "llmLatencyMs": 850,
  "inputTokens": 1240,
  "outputTokens": 380,
  "model": "llama3.2:3b"
}
```

#### Stream a response (SSE)
```http
POST /api/chat/stream
Authorization: Bearer <token>
Content-Type: application/json
Accept: text/event-stream

{ "question": "Summarize the document" }
```

#### Semantic search
```http
POST /api/search
Authorization: Bearer <token>
Content-Type: application/json

{
  "query": "machine learning techniques",
  "topK": 10,
  "minSimilarity": 0.6
}
```

---

## Configuration Reference

Key `application.yml` properties:

| Property | Default | Description |
|----------|---------|-------------|
| `app.retrieval.top-k` | 5 | Max chunks to retrieve |
| `app.retrieval.min-similarity` | 0.7 | Cosine similarity threshold |
| `app.retrieval.hybrid-search-enabled` | true | Vector + keyword search |
| `app.chunking.strategy` | recursive | fixed \| recursive \| semantic |
| `app.chunking.chunk-size` | 1000 | Characters per chunk |
| `app.chunking.chunk-overlap` | 200 | Overlap between chunks |
| `app.security.jwt.expiration-ms` | 86400000 | Access token TTL (24h) |
| `spring.ai.ollama.base-url` (`OLLAMA_BASE_URL`) | `http://ollama:11434` | Ollama server URL |
| `spring.ai.ollama.chat.options.model` (`AI_CHAT_MODEL`) | `llama3.2:3b` | LLM model |
| `spring.ai.ollama.embedding.options.model` (`AI_EMBEDDING_MODEL`) | `nomic-embed-text` | Embedding model |
| `spring.ai.vectorstore.pgvector.dimensions` | 768 | Must match the embedding model's output size |

---

## Database Schema

```sql
users          -- Authentication + roles
user_roles     -- Role assignments
documents      -- Uploaded document metadata (status: UPLOADED→PROCESSING→INDEXED)
chunks         -- Text chunks with vector(768) embeddings
conversations  -- Chat session threads per user
messages       -- USER/ASSISTANT/SYSTEM messages per conversation
audit_logs     -- Immutable security audit trail
```

Vector index: `HNSW` with `cosine_distance` on `chunks.embedding`

---

## Security

- **JWT Bearer tokens** — HMAC-SHA256 signed, configurable TTL
- **Role-based access** — `USER` (default) and `ADMIN` roles
- **Rate limiting** — per-IP token bucket (configurable per endpoint)
- **Audit logging** — async, non-blocking, REQUIRES_NEW transaction
- **BCrypt** — work factor 12 for password hashing
- **Stateless** — no server-side sessions

---

## Monitoring

Prometheus metrics exposed at `/api/actuator/prometheus`:

| Metric | Description |
|--------|-------------|
| `rag.embedding.latency` | Ollama embedding call latency |
| `rag.retrieval.latency` | Vector search latency |
| `rag.llm.latency` | Ollama chat response latency |
| `rag.chat.total.latency` | End-to-end chat latency |
| `rag.indexing.documents` | Documents indexed counter |
| `rag.indexing.chunks` | Chunks created counter |
| `rag.llm.tokens.input` | Input tokens consumed |
| `rag.llm.tokens.output` | Output tokens generated |

---

## Testing

```bash
# Unit tests only (fast, no Docker required)
./mvnw test -Dtest="**/unit/**"

# Integration tests (requires Docker for Testcontainers)
./mvnw test -Dtest="**/integration/**"

# All tests
./mvnw test

# With coverage report
./mvnw verify
```

---

## Building for Production

```bash
# Build JAR
./mvnw package -DskipTests

# Build Docker image
docker build -t rag-application:1.0.0 .

# Run container (point OLLAMA_BASE_URL at a reachable Ollama instance)
docker run -p 8080:8080 \
  -e OLLAMA_BASE_URL=http://ollama:11434 \
  -e AI_CHAT_MODEL=llama3.2:3b \
  -e AI_EMBEDDING_MODEL=nomic-embed-text \
  -e DATABASE_URL=jdbc:postgresql://host:5432/ragdb \
  -e DATABASE_USERNAME=raguser \
  -e DATABASE_PASSWORD=secret \
  -e REDIS_HOST=redis \
  -e JWT_SECRET=your-secret \
  rag-application:1.0.0
```

> The `app` container needs network access to an Ollama server with the
> `llama3.2:3b` and `nomic-embed-text` models already pulled — either the bundled
> `ollama` compose service or an external instance.

---

## Bonus Features Implemented

| Feature | Implementation |
|---------|----------------|
| **Hybrid Search** | Vector (cosine) + BM25 keyword with configurable weights |
| **Streaming** | Spring WebFlux + SSE via `/chat/stream` |
| **Context Compression** | Trims retrieved context to fit max token budget |
| **Conversation Memory** | Full message history per conversation, configurable window |
| **Rate Limiting** | Token bucket per IP, separate limits for chat vs upload |
| **Async Indexing** | Background thread pool, non-blocking to the caller |
| **Document Versioning** | `version` field, re-index clears old chunks |
| **Audit Logging** | Async, REQUIRES_NEW, immutable `audit_logs` table |
| **Multi-tenant** | `tenant_id` on all entities, `X-Tenant-ID` header |
| **Prompt Templates** | Configurable system + no-context prompts in `application.yml` |
| **AI Observability** | Micrometer timers for embed/retrieve/llm + Prometheus/Grafana |
| **Caching** | Redis cache for embeddings (24h TTL), docs, search results |
| **pgvector HNSW** | HNSW index (`m=16, ef_construction=64`) for fast ANN search |

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21, Spring Boot 3.3.x |
| AI | Spring AI 1.0.1, local Ollama (llama3.2:3b chat + nomic-embed-text embeddings) |
| Database | PostgreSQL 16 + pgvector extension |
| Cache | Redis 7.2 |
| Security | Spring Security 6, JWT (jjwt 0.12.x), BCrypt |
| Parsing | Apache Tika, PDFBox 3, Apache POI 5, Flexmark |
| Monitoring | Spring Actuator, Micrometer, Prometheus, Grafana |
| Testing | JUnit 5, Mockito, Testcontainers, AssertJ |
| Build | Maven 3.9, Docker multi-stage build |
| Code gen | Lombok, MapStruct |
