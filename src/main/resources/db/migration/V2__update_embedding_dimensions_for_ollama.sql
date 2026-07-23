-- ============================================================
-- V2 — Update embedding column dimensions for Ollama
-- The application now generates embeddings with the Ollama
-- "nomic-embed-text" model (768 dimensions) instead of OpenAI's
-- text-embedding-3-* models (1536 dimensions). The vector column
-- and its HNSW index must match the new dimensionality, and any
-- embeddings computed with the old model are no longer compatible
-- and must be cleared and regenerated.
-- ============================================================

-- Drop the HNSW index first; it is bound to the current column type.
DROP INDEX IF EXISTS idx_chunks_embedding_hnsw;

-- Existing 1536-dim vectors are incompatible with a 768-dim model, so they
-- must be cleared before the column can be resized. Documents will need to
-- be re-indexed (re-embedded) after this migration runs.
UPDATE chunks SET embedding = NULL;

ALTER TABLE chunks
    ALTER COLUMN embedding TYPE vector(768);

-- Recreate the HNSW index for the new dimensionality.
CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw
    ON chunks USING hnsw(embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
