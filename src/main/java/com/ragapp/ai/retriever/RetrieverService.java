package com.ragapp.ai.retriever;

import com.ragapp.ai.embedding.EmbeddingService;
import com.ragapp.config.AppProperties;
import com.ragapp.dto.ChatDto;
import com.ragapp.entity.Chunk;
import com.ragapp.entity.Document;
import com.ragapp.repository.ChunkRepository;
import com.ragapp.repository.DocumentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Retrieves relevant document chunks for a given query using vector similarity search
 * and, optionally, a hybrid vector + keyword search.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrieverService {

    private final EmbeddingService   embeddingService;
    private final ChunkRepository    chunkRepository;
    private final DocumentRepository documentRepository;
    private final AppProperties      props;
    private final MeterRegistry      meterRegistry;

    /**
     * Main retrieval entry-point. Delegates to hybrid or vector-only search
     * based on configuration.
     *
     * @param query       Natural-language question
     * @param filter      Optional metadata filters
     * @param topK        Max results to return (null → use config default)
     * @param minSim      Min cosine similarity (null → use config default)
     * @param documentIds Optional whitelist of document IDs to search within
     */
    @Transactional(readOnly = true)
    public List<ChatDto.RetrievedChunk> retrieve(String query,
                                                  ChatDto.MetadataFilter filter,
                                                  Integer topK,
                                                  Double  minSim,
                                                  List<String> documentIds) {

        int    k   = topK   != null ? topK   : props.getRetrieval().getTopK();
        double sim = minSim != null ? minSim : props.getRetrieval().getMinSimilarity();

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            float[] queryEmbedding = embeddingService.embed(query);
            String  vectorLiteral  = EmbeddingService.toVectorLiteral(queryEmbedding);

            List<ChatDto.RetrievedChunk> results;

            if (props.getRetrieval().isHybridSearchEnabled()) {
                results = hybridSearch(vectorLiteral, query, k, sim, documentIds);
            } else {
                results = vectorSearch(vectorLiteral, k, sim, documentIds);
            }

            if (props.getRetrieval().isContextCompressionEnabled()) {
                results = compressContext(results, query);
            }

            meterRegistry.counter("rag.retrieval.results").increment(results.size());
            log.info("Retrieved {} chunks for query (topK={}, minSimilarity={})", results.size(), k, sim);
            return results;

        } finally {
            sample.stop(meterRegistry.timer("rag.retrieval.latency"));
        }
    }

    // ─── Private Search Implementations ──────────────────────────────────────

    private List<ChatDto.RetrievedChunk> vectorSearch(String vectorLiteral, int topK,
                                                       double minSim, List<String> docIds) {
        List<Object[]> rows;

        if (docIds != null && !docIds.isEmpty()) {
            String pgArray = docIds.stream().collect(Collectors.joining(",", "{", "}"));
            rows = chunkRepository.vectorSearchWithDocumentFilter(vectorLiteral, pgArray, topK, minSim);
        } else {
            rows = chunkRepository.vectorSearch(vectorLiteral, topK, minSim);
        }

        return mapRows(rows, false);
    }

    private List<ChatDto.RetrievedChunk> hybridSearch(String vectorLiteral, String query,
                                                       int topK, double minSim, List<String> docIds) {
        // The hybrid SQL has no document-id predicate; keep whitelisted searches scoped.
        if (docIds != null && !docIds.isEmpty()) {
            return vectorSearch(vectorLiteral, topK, minSim, docIds);
        }
        double vw = props.getRetrieval().getVectorWeight();
        double kw = props.getRetrieval().getKeywordWeight();
        List<Object[]> rows = chunkRepository.hybridSearch(vectorLiteral, query, topK, minSim, vw, kw);
        return mapRows(rows, true);
    }

    @SuppressWarnings("unchecked")
    private List<ChatDto.RetrievedChunk> mapRows(List<Object[]> rows, boolean isHybrid) {
        List<ChatDto.RetrievedChunk> results = new ArrayList<>();
        for (Object[] row : rows) {
            UUID   chunkId    = UUID.fromString(row[0].toString());
            UUID   documentId = UUID.fromString(row[1].toString());
            String chunkText  = (String) row[2];
            int    chunkIndex = ((Number) row[3]).intValue();
            Integer pageNumber = row[4] != null ? ((Number) row[4]).intValue() : null;
            Map<String, Object> metadata = row[5] instanceof Map<?, ?> rawMetadata
                    ? rawMetadata.entrySet().stream().collect(Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()), Map.Entry::getValue))
                    : Collections.emptyMap();
            double score = ((Number) row[row.length - 1]).doubleValue();

            // Resolve document name
            String documentName = documentRepository.findById(documentId)
                    .map(Document::getOriginalFilename)
                    .orElse("Unknown");

            results.add(new ChatDto.RetrievedChunk(
                    chunkId, documentId, documentName, chunkText,
                    score, chunkIndex, pageNumber, metadata));
        }
        return results;
    }

    /**
     * Context compression: truncates chunks so total token count
     * stays within the configured context window.
     */
    private List<ChatDto.RetrievedChunk> compressContext(List<ChatDto.RetrievedChunk> chunks, String query) {
        int maxTokens = props.getRetrieval().getMaxContextTokens();
        int accumulated = estimateTokens(query);
        List<ChatDto.RetrievedChunk> compressed = new ArrayList<>();

        for (ChatDto.RetrievedChunk chunk : chunks) {
            int chunkTokens = estimateTokens(chunk.chunkText());
            if (accumulated + chunkTokens <= maxTokens) {
                compressed.add(chunk);
                accumulated += chunkTokens;
            } else {
                // Partial inclusion: trim the chunk to fit
                int remaining = maxTokens - accumulated;
                if (remaining > 100) {
                    String trimmed = trimToTokens(chunk.chunkText(), remaining);
                    compressed.add(new ChatDto.RetrievedChunk(
                            chunk.chunkId(), chunk.documentId(), chunk.documentName(),
                            trimmed, chunk.similarityScore(), chunk.chunkIndex(),
                            chunk.pageNumber(), chunk.metadata()));
                }
                break;
            }
        }

        log.debug("Context compression: {} → {} chunks, ~{} tokens", chunks.size(), compressed.size(), accumulated);
        return compressed;
    }

    /** Rough token estimator: ~4 chars per token (GPT tokenisation heuristic). */
    private int estimateTokens(String text) {
        return text == null ? 0 : text.length() / 4;
    }

    private String trimToTokens(String text, int maxTokens) {
        int maxChars = maxTokens * 4;
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }
}
