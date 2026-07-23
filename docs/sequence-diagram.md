# RAG Application — Sequence Diagrams

## 1. Document Upload & Indexing Flow

```
Client          Controller         DocumentService      IndexingService    EmbeddingService   PostgreSQL
  │                  │                   │                    │                  │               │
  │─POST /upload────►│                   │                    │                  │               │
  │                  │──upload()────────►│                    │                  │               │
  │                  │                   │──parse(file)        │                  │               │
  │                  │                   │──save(Document)────►│                  │──INSERT──────►│
  │                  │                   │                    │                  │               │
  │◄─201 CREATED────│                   │                    │                  │               │
  │                  │                   │                    │                  │               │
  │─POST /index─────►│                   │                    │                  │               │
  │                  │──index()─────────►│──indexSync()───────►│                  │               │
  │                  │                   │                    │──chunk(text)      │               │
  │                  │                   │                    │──embedBatch()────►│               │
  │                  │                   │                    │                  │──Ollama API   │
  │                  │                   │                    │                  │◄──embeddings──│
  │                  │                   │                    │──saveAll(chunks)─►│               │
  │                  │                   │                    │                  │──INSERT──────►│
  │                  │                   │                    │──markAsIndexed───►│──UPDATE─────►│
  │◄─200 INDEXED────│                   │                    │                  │               │
```

## 2. RAG Chat Flow

```
Client        ChatController     ChatService       RetrieverService    LlmClient      PostgreSQL
  │                │                  │                  │                 │               │
  │─POST /chat────►│                  │                  │                 │               │
  │                │──chat()─────────►│                  │                 │               │
  │                │                  │──resolve conv────►│                 │──SELECT──────►│
  │                │                  │──load history────►│                 │──SELECT──────►│
  │                │                  │                  │                 │               │
  │                │                  │──retrieve()──────►│                 │               │
  │                │                  │                  │──embed(question)►│               │
  │                │                  │                  │                 │──Ollama Embed │
  │                │                  │                  │◄──embedding─────│               │
  │                │                  │                  │──hybridSearch───►│──SELECT(vec)─►│
  │                │                  │◄──chunks──────────│                 │               │
  │                │                  │                  │                 │               │
  │                │                  │──chat()─────────────────────────────►│               │
  │                │                  │                  │                 │──buildPrompt   │
  │                │                  │                  │                 │──Ollama Chat  │
  │                │                  │◄──LlmResponse────────────────────────│               │
  │                │                  │                  │                 │               │
  │                │                  │──persistMessage──►│                 │──INSERT──────►│
  │◄─200 + answer─│                  │                  │                 │               │
```

## 3. Authentication Flow

```
Client          AuthController     AuthService        JwtTokenProvider   UserRepository
  │                  │                  │                    │                  │
  │─POST /register──►│                  │                    │                  │
  │                  │──register()─────►│                    │                  │
  │                  │                  │──existsByUsername──►│                  │
  │                  │                  │──save(User)────────►│                  │
  │                  │                  │──generateAccess────►│                  │
  │                  │                  │──generateRefresh───►│                  │
  │◄─201 + tokens───│                  │                    │                  │
  │                  │                  │                    │                  │
  │─POST /login─────►│                  │                    │                  │
  │                  │──login()────────►│                    │                  │
  │                  │                  │──authenticate()     │                  │
  │                  │                  │──findById──────────►│──SELECT─────────►│
  │                  │                  │──updateLastLogin────►│──UPDATE─────────►│
  │                  │                  │──generateAccess────►│                  │
  │◄─200 + tokens───│                  │                    │                  │
  │                  │                  │                    │                  │
  │─Bearer token────►│ (all other APIs) │                    │                  │
  │                  │ JwtFilter runs   │                    │                  │
  │                  │ validateToken──────────────────────────►│                  │
  │                  │ loadUser────────►│                    │                  │
```

## 4. Hybrid Search Strategy

```
Query
  │
  ├── Generate embedding ──────────────────────────────────► Ollama API
  │                                                                │
  │◄── float[768] ──────────────────────────────────────────────── │
  │
  ├── Vector search (cosine similarity via HNSW index)
  │   sql: 1 - (embedding <=> query_vector)
  │   weight: vectorWeight (default 0.7)
  │
  ├── Keyword search (PostgreSQL full-text ts_rank)
  │   sql: ts_rank(to_tsvector('english', chunk_text), plainto_tsquery(query))
  │   weight: keywordWeight (default 0.3)
  │
  └── Combined score = (vectorWeight * vector_score) + (keywordWeight * keyword_score)
      ORDER BY combined_score DESC
      LIMIT top-k
      WHERE combined_score >= minSimilarity
```

## 5. Chunking Strategies

```
Input Text
    │
    ├── FixedSizeChunker
    │     Split by character count (chunkSize)
    │     Slide by (chunkSize - overlap)
    │     Fast, predictable, ignores semantics
    │
    ├── RecursiveChunker (DEFAULT)
    │     Try separators in order: \n\n → \n → ". " → " " → ""
    │     Merge small chunks up to chunkSize
    │     Carry overlap from previous chunk
    │     Respects paragraph → sentence → word boundaries
    │
    └── SemanticChunker
          Split at sentence boundaries (regex: [.!?]\s+)
          Group sentences until chunkSize is reached
          Carry overlap from sentence tail
          Best semantic coherence
```
