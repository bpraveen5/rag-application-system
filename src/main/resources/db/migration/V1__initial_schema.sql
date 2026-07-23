-- ============================================================
-- V1 — Initial Schema for RAG Application
-- PostgreSQL 15+ with pgvector extension
-- ============================================================

-- Enable pgvector
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- for full-text trigram search

-- ============================================================
-- USERS
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id                  UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    username            VARCHAR(50)  NOT NULL UNIQUE,
    email               VARCHAR(255) NOT NULL UNIQUE,
    password_hash       TEXT         NOT NULL,
    full_name           VARCHAR(255),
    tenant_id           VARCHAR(100),
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    account_non_locked  BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role    VARCHAR(50)  NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE INDEX IF NOT EXISTS idx_users_tenant_id ON users(tenant_id);
CREATE INDEX IF NOT EXISTS idx_users_email     ON users(email);

-- ============================================================
-- DOCUMENTS
-- ============================================================
CREATE TABLE IF NOT EXISTS documents (
    id                  UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    filename            VARCHAR(512) NOT NULL,
    original_filename   VARCHAR(512) NOT NULL,
    content_type        VARCHAR(255) NOT NULL,
    file_size           BIGINT,
    file_path           VARCHAR(1024),
    user_id             UUID         NOT NULL,
    tenant_id           VARCHAR(100),
    status              VARCHAR(50)  NOT NULL DEFAULT 'UPLOADED',
    version             INTEGER      NOT NULL DEFAULT 1,
    chunk_count         INTEGER,
    error_message       VARCHAR(2048),
    metadata            JSONB,
    upload_date         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,
    indexed_at          TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_documents_user_id    ON documents(user_id);
CREATE INDEX IF NOT EXISTS idx_documents_tenant_id  ON documents(tenant_id);
CREATE INDEX IF NOT EXISTS idx_documents_status     ON documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_filename   ON documents(filename);
CREATE INDEX IF NOT EXISTS idx_documents_upload_date ON documents(upload_date DESC);
-- GIN index for JSONB metadata filtering
CREATE INDEX IF NOT EXISTS idx_documents_metadata   ON documents USING GIN(metadata);

-- ============================================================
-- CHUNKS
-- ============================================================
CREATE TABLE IF NOT EXISTS chunks (
    id           UUID     PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id  UUID     NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_text   TEXT     NOT NULL,
    -- Use 1536 dimensions for compatibility with pgvector HNSW on this environment.
    embedding    vector(1536),
    chunk_index  INTEGER  NOT NULL,
    token_count  INTEGER,
    char_count   INTEGER,
    page_number  INTEGER,
    metadata     JSONB,
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_chunks_document_id  ON chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_chunk_index  ON chunks(document_id, chunk_index);
-- GIN index for keyword (full-text) search component of hybrid search
CREATE INDEX IF NOT EXISTS idx_chunks_text_search
    ON chunks USING GIN(to_tsvector('english', chunk_text));
-- HNSW index for fast approximate nearest-neighbour search (cosine distance)
CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw
    ON chunks USING hnsw(embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- ============================================================
-- CONVERSATIONS
-- ============================================================
CREATE TABLE IF NOT EXISTS conversations (
    id          UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID         NOT NULL,
    tenant_id   VARCHAR(100),
    title       VARCHAR(512),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_conversations_user_id    ON conversations(user_id);
CREATE INDEX IF NOT EXISTS idx_conversations_tenant_id  ON conversations(tenant_id);
CREATE INDEX IF NOT EXISTS idx_conversations_created_at ON conversations(created_at DESC);

-- ============================================================
-- MESSAGES
-- ============================================================
CREATE TABLE IF NOT EXISTS messages (
    id               UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id  UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role             VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    content          TEXT        NOT NULL,
    token_count      INTEGER,
    retrieval_score  DOUBLE PRECISION,
    sources          TEXT,
    timestamp        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation_id ON messages(conversation_id);
CREATE INDEX IF NOT EXISTS idx_messages_timestamp       ON messages(timestamp ASC);

-- ============================================================
-- AUDIT LOGS
-- ============================================================
CREATE TABLE IF NOT EXISTS audit_logs (
    id             UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id        UUID,
    username       VARCHAR(100),
    tenant_id      VARCHAR(100),
    action         VARCHAR(100) NOT NULL,
    resource_type  VARCHAR(100),
    resource_id    VARCHAR(255),
    ip_address     VARCHAR(50),
    user_agent     VARCHAR(512),
    outcome        VARCHAR(20)  NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'UNAUTHORIZED')),
    details        TEXT,
    metadata       JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_user_id     ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_action      ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_resource_id ON audit_logs(resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_created_at  ON audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_tenant_id   ON audit_logs(tenant_id);

-- ============================================================
-- SEED: Default admin user
-- Password: Admin@12345! (bcrypt hash — change before production)
-- ============================================================
INSERT INTO users (id, username, email, password_hash, full_name, enabled, account_non_locked, created_at)
VALUES (
    uuid_generate_v4(),
    'admin',
    'admin@ragapp.example.com',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LeAoMkgvBjXqvFsCC',  -- Admin@12345!
    'System Administrator',
    TRUE,
    TRUE,
    NOW()
)
ON CONFLICT (username) DO NOTHING;

INSERT INTO user_roles (user_id, role)
SELECT id, 'ADMIN' FROM users WHERE username = 'admin'
ON CONFLICT DO NOTHING;

INSERT INTO user_roles (user_id, role)
SELECT id, 'USER' FROM users WHERE username = 'admin'
ON CONFLICT DO NOTHING;
