# RAG Application

**Production-ready Retrieval-Augmented Generation (RAG) API** built with Spring Boot 3.x, Java 17, Spring AI, a local Ollama LLM (llama3.2:3b), PostgreSQL + pgvector, and Redis.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                         RAG Application                              │
│                                                                      │
│  REST Controllers  →  Services  →  AI Layer  →  Repositories         │
│  ─────────────────────────────────────────────────────────────────   │
│  AuthController        AuthService     EmbeddingService   UserRepo   │
│  DocumentController    DocumentService  RetrieverService   DocRepo   │
│  ChatController        IndexingService  PromptBuilder      ChunkRepo │
│  HealthController      ChatService      LlmClient          ConvRepo  │
│                        AuditService                        MsgRepo   │
│                                                                      │
│  Security: JWT Filter → UserDetailsService → BCrypt                  │
│  Chunking:  FixedSize | Recursive | Semantic                         │
│  Parsing:   PDF | DOCX | TXT | Markdown | HTML  (Tika fallback)      │
│  Cache:     Redis  (embeddings, search, docs, conversations)         │
│  Metrics:   Micrometer → Prometheus → Grafana                        │
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
| App Health   | http://localhost:8080/api/health  |
| Swagger UI   | http://localhost:8080/api/swagger-ui.html |
| Prometheus   | http://localhost:9090        |
| Prometheus   | http://localhost:9090/targets?search=       |
| Grafana      | http://localhost:3000        |
| Grafana      | http://localhost:3000/connections/datasources      |
| Grafana      | http://localhost:3000/dashboards        |
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
  "username": "bpraveen",
  "email": "bpraveen@gmail.com",
  "password": "Password123",
  "fullName": "B Praveen"
}
```

#### Login
```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "praveen",
  "password": "*********"
}

Response:
{
    "accessToken": "eyJhbGciOiJIUzM4NCJ9........",
    "refreshToken": "eyJhbGciOiJIUzM4NCJ9........",
    "tokenType": "Bearer",
    "expiresIn": 86400,
    "user": {
        "id": "baa2177f-6315-433b-b107-1869dde68b00",
        "username": "praveen",
        "email": "praveen@gmail.com",
        "fullName": "Praveen Kumar",
        "roles": [
            "USER"
        ],
        "createdAt": "2026-07-21T09:08:47.904264Z"
    }
}
```

### Documents

#### Upload a document
```http
POST /api/documents/upload?index=true
Authorization: Bearer <token>
Content-Type: multipart/form-data

file=@/path/to/document.pdf

{
    "documentId": "b6af2336-e5ff-4573-b22d-18d2b431a8f5",
    "filename": "db_introduction_applications.docx",
    "contentType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "fileSize": 22365,
    "status": "UPLOADED",
    "message": "Document uploaded and indexing started in background."
}
```

#### Index a document
```http
POST /api/documents/index
Authorization: Bearer <token>
Content-Type: application/json

{
  "documentId": "a684d624-c1ae-42b4-8f8b-78cef56c1035"
}
Response:
{
    "documentId": "a684d624-c1ae-42b4-8f8b-78cef56c1035",
    "status": "INDEXED",
    "chunksCreated": 6,
    "processingTimeMs": 2953
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
  "question": "what is sql?",
  "conversationId":  null,
  "topK": 5,
  "minSimilarity": 0.3
}

Response:
{
    "conversationId": "9a824e08-991a-4e39-894b-e9baf01ee5e1",
    "messageId": "4e64fc07-cf4d-4844-988b-281b773106cd",
    "answer": "According to the provided context, SQL (Structured Query Language) is not explicitly mentioned. However, it can be inferred that SQL is related to database management systems.\n\nThe source document \"db_introduction_applications.docx\" discusses Database Management Systems (DBMS), but does not specifically mention SQL.\n\nI don't have enough information in the knowledge base to answer that.",
    "sources": [
        {
            "chunkId": "feb566f7-fd1f-4c00-9bd4-1881729f817b",
            "documentId": "b6af2336-e5ff-4573-b22d-18d2b431a8f5",
            "documentName": "db_introduction_applications.docx",
            "chunkText": "s realised that they needed a better solution to this problem.\nLarry Ellison, the co-founder of Oracle was amongst the first few, who realised the need for a software based Database Management System. What is DBMS?\nA DBMS is a software that allows creation, definition and manipulation of database, allowing users to store, process and analyse data easily. \nDBMS provides us with an interface or a tool, to perform various operations like creating database, storing data in it, updating data, creating tables in the database and a lot more.\nDBMS also provides protection and security to the databases. It also maintains data consistency in case of multiple users.\nHere are some examples of popular DBMS used these days:\nMySql\nOracle\nSQL Server\nIBM DB2\nPostgreSQL\nAmazon SimpleDB (cloud based) etc. Characteristics of Database Management System A database management system has following characteristics:",
            "similarityScore": 0.4667019176313392,
            "chunkIndex": 2,
            "pageNumber": null,
            "metadata": {}
        },
        {
            "chunkId": "5b184976-b5a8-4c43-be8c-15a09703fc96",
            "documentId": "b0c6aa57-5828-419f-972c-147cf8d9b475",
            "documentName": "db_introduction_applications.docx",
            "chunkText": "s realised that they needed a better solution to this problem.\nLarry Ellison, the co-founder of Oracle was amongst the first few, who realised the need for a software based Database Management System. What is DBMS?\nA DBMS is a software that allows creation, definition and manipulation of database, allowing users to store, process and analyse data easily. \nDBMS provides us with an interface or a tool, to perform various operations like creating database, storing data in it, updating data, creating tables in the database and a lot more.\nDBMS also provides protection and security to the databases. It also maintains data consistency in case of multiple users.\nHere are some examples of popular DBMS used these days:\nMySql\nOracle\nSQL Server\nIBM DB2\nPostgreSQL\nAmazon SimpleDB (cloud based) etc. Characteristics of Database Management System A database management system has following characteristics:",
            "similarityScore": 0.4667019176313392,
            "chunkIndex": 2,
            "pageNumber": null,
            "metadata": {}
        },
        {
            "chunkId": "358f4dd4-f2ea-44eb-bebd-9b00fbc002ce",
            "documentId": "a684d624-c1ae-42b4-8f8b-78cef56c1035",
            "documentName": "db_introduction_applications.docx",
            "chunkText": "s realised that they needed a better solution to this problem.\nLarry Ellison, the co-founder of Oracle was amongst the first few, who realised the need for a software based Database Management System. What is DBMS?\nA DBMS is a software that allows creation, definition and manipulation of database, allowing users to store, process and analyse data easily. \nDBMS provides us with an interface or a tool, to perform various operations like creating database, storing data in it, updating data, creating tables in the database and a lot more.\nDBMS also provides protection and security to the databases. It also maintains data consistency in case of multiple users.\nHere are some examples of popular DBMS used these days:\nMySql\nOracle\nSQL Server\nIBM DB2\nPostgreSQL\nAmazon SimpleDB (cloud based) etc. Characteristics of Database Management System A database management system has following characteristics:",
            "similarityScore": 0.4667019176313392,
            "chunkIndex": 2,
            "pageNumber": null,
            "metadata": {}
        },
        {
            "chunkId": "66f7ff53-a539-4103-a767-fe0ed4dbef43",
            "documentId": "b0c6aa57-5828-419f-972c-147cf8d9b475",
            "documentName": "db_introduction_applications.docx",
            "chunkText": "used these days:\nMySql\nOracle\nSQL Server\nIBM DB2\nPostgreSQL\nAmazon SimpleDB (cloud based) etc. Characteristics of Database Management System A database management system has following characteristics: Data stored into Tables: Data is never directly stored into the database. Data is stored into tables, created inside the database. DBMS also allows to have relationships between tables which makes the data more meaningful and connected. You can easily understand what type of data is stored where by looking at all the tables created in a database. Reduced Redundancy: In the modern world hard drives are very cheap, but earlier when hard drives were too expensive, unnecessary repetition of data in database was a big problem. But DBMS follows Normalisation which divides the data in such a way that repetition is minimum.",
            "similarityScore": 0.4589887529461737,
            "chunkIndex": 3,
            "pageNumber": null,
            "metadata": {}
        },
        {
            "chunkId": "b9be760a-be17-4d5f-bb4a-06a8328d7f3b",
            "documentId": "b6af2336-e5ff-4573-b22d-18d2b431a8f5",
            "documentName": "db_introduction_applications.docx",
            "chunkText": "used these days:\nMySql\nOracle\nSQL Server\nIBM DB2\nPostgreSQL\nAmazon SimpleDB (cloud based) etc. Characteristics of Database Management System A database management system has following characteristics: Data stored into Tables: Data is never directly stored into the database. Data is stored into tables, created inside the database. DBMS also allows to have relationships between tables which makes the data more meaningful and connected. You can easily understand what type of data is stored where by looking at all the tables created in a database. Reduced Redundancy: In the modern world hard drives are very cheap, but earlier when hard drives were too expensive, unnecessary repetition of data in database was a big problem. But DBMS follows Normalisation which divides the data in such a way that repetition is minimum.",
            "similarityScore": 0.4589887529461737,
            "chunkIndex": 3,
            "pageNumber": null,
            "metadata": {}
        }
    ],
    "sourcesUsed": 5,
    "retrievalLatencyMs": 687,
    "llmLatencyMs": 177710,
    "inputTokens": 1111,
    "outputTokens": 74,
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
  "query": "sql",
  "topK": 5,
  "minSimilarity": 0.3
}
Response:
{
    "query": "sql",
    "results": [
        {
            "chunkId": "ca9664ba-31fc-49f5-b4ef-b3af91a16810",
            "documentId": "a684d624-c1ae-42b4-8f8b-78cef56c1035",
            "documentName": "db_introduction_applications.docx",
            "chunkText": "used these days:\nMySql\nOracle\nSQL Server\nIBM DB2\nPostgreSQL\nAmazon SimpleDB (cloud based) etc. Characteristics of Database Management System A database management system has following characteristics: Data stored into Tables: Data is never directly stored into the database. Data is stored into tables, created inside the database. DBMS also allows to have relationships between tables which makes the data more meaningful and connected. You can easily understand what type of data is stored where by looking at all the tables created in a database. Reduced Redundancy: In the modern world hard drives are very cheap, but earlier when hard drives were too expensive, unnecessary repetition of data in database was a big problem. But DBMS follows Normalisation which divides the data in such a way that repetition is minimum.",
            "similarityScore": 0.46346040191767085,
            "chunkIndex": 3,
            "pageNumber": null,
            "metadata": {}
        },
        {
            "chunkId": "66f7ff53-a539-4103-a767-fe0ed4dbef43",
            "documentId": "b0c6aa57-5828-419f-972c-147cf8d9b475",
            "documentName": "db_introduction_applications.docx",
            "chunkText": "used these days:\nMySql\nOracle\nSQL Server\nIBM DB2\nPostgreSQL\nAmazon SimpleDB (cloud based) etc. Characteristics of Database Management System A database management system has following characteristics: Data stored into Tables: Data is never directly stored into the database. Data is stored into tables, created inside the database. DBMS also allows to have relationships between tables which makes the data more meaningful and connected. You can easily understand what type of data is stored where by looking at all the tables created in a database. Reduced Redundancy: In the modern world hard drives are very cheap, but earlier when hard drives were too expensive, unnecessary repetition of data in database was a big problem. But DBMS follows Normalisation which divides the data in such a way that repetition is minimum.",
            "similarityScore": 0.46346040191767085,
            "chunkIndex": 3,
            "pageNumber": null,
            "metadata": {}
        },
        {
            "chunkId": "5b184976-b5a8-4c43-be8c-15a09703fc96",
            "documentId": "b0c6aa57-5828-419f-972c-147cf8d9b475",
            "documentName": "db_introduction_applications.docx",
            "chunkText": "s realised that they needed a better solution to this problem.\nLarry Ellison, the co-founder of Oracle was amongst the first few, who realised the need for a software based Database Management System. What is DBMS?\nA DBMS is a software that allows creation, definition and manipulation of database, allowing users to store, process and analyse data easily. \nDBMS provides us with an interface or a tool, to perform various operations like creating database, storing data in it, updating data, creating tables in the database and a lot more.\nDBMS also provides protection and security to the databases. It also maintains data consistency in case of multiple users.\nHere are some examples of popular DBMS used these days:\nMySql\nOracle\nSQL Server\nIBM DB2\nPostgreSQL\nAmazon SimpleDB (cloud based) etc. Characteristics of Database Management System A database management system has following characteristics:",
            "similarityScore": 0.4373554368010194,
            "chunkIndex": 2,
            "pageNumber": null,
            "metadata": {}
        },
        {
            "chunkId": "8c5c932e-62a5-4bf7-b7f7-5ca76ce3d33c",
            "documentId": "a684d624-c1ae-42b4-8f8b-78cef56c1035",
            "documentName": "db_introduction_applications.docx",
            "chunkText": "s realised that they needed a better solution to this problem.\nLarry Ellison, the co-founder of Oracle was amongst the first few, who realised the need for a software based Database Management System. What is DBMS?\nA DBMS is a software that allows creation, definition and manipulation of database, allowing users to store, process and analyse data easily. \nDBMS provides us with an interface or a tool, to perform various operations like creating database, storing data in it, updating data, creating tables in the database and a lot more.\nDBMS also provides protection and security to the databases. It also maintains data consistency in case of multiple users.\nHere are some examples of popular DBMS used these days:\nMySql\nOracle\nSQL Server\nIBM DB2\nPostgreSQL\nAmazon SimpleDB (cloud based) etc. Characteristics of Database Management System A database management system has following characteristics:",
            "similarityScore": 0.4373554368010194,
            "chunkIndex": 2,
            "pageNumber": null,
            "metadata": {}
        },
        {
            "chunkId": "b86e1779-a163-4375-bb61-2a1c296cb622",
            "documentId": "b0c6aa57-5828-419f-972c-147cf8d9b475",
            "documentName": "db_introduction_applications.docx",
            "chunkText": ", protecting the data from un-authorised access. In a typical DBMS, we can create user accounts with different access permissions, using which we can easily secure our data by restricting user access. DBMS supports transactions, which allows us to better handle and manage data integrity in real world applications where multi-threading is extensively used. Advantages of DBMS\nSegregation of applicaion program.\nMinimal data duplicacy or data redundancy.\nEasy retrieval of data using the Query Language.\nReduced development time and maintainance need.\nWith Cloud Datacenters, we now have Database Management Systems capable of storing almost infinite data.\nSeamless integration into the application programming languages which makes it very easier to add a database to almost any application or website. Disadvantages of DBMS\nIt's Complexity\nExcept MySQL, which is open source, licensed DBMSs are generally costly.\nThey are large in size.",
            "similarityScore": 0.43412906468493523,
            "chunkIndex": 5,
            "pageNumber": null,
            "metadata": {}
        }
    ],
    "totalResults": 5,
    "latencyMs": 17
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
| Runtime | Java 17, Spring Boot 3.3.x |
| AI | Spring AI 1.0.1, local Ollama (llama3.2:3b chat + nomic-embed-text embeddings) |
| Database | PostgreSQL 16 + pgvector extension |
| Cache | Redis 7.2 |
| Security | Spring Security 6, JWT (jjwt 0.12.x), BCrypt |
| Parsing | Apache Tika, PDFBox 3, Apache POI 5, Flexmark |
| Monitoring | Spring Actuator, Micrometer, Prometheus, Grafana |
| Testing | JUnit 5, Mockito, Testcontainers, AssertJ |
| Build | Maven 3.9, Docker multi-stage build |
| Code gen | Lombok, MapStruct |
