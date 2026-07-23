package com.ragapp.chunking;

import com.ragapp.config.AppProperties;
import com.ragapp.exception.InvalidFileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Façade that selects the correct chunking strategy from configuration
 * and delegates to the appropriate {@link Chunker} implementation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkingService {

    private final AppProperties     props;
    private final FixedSizeChunker  fixedSizeChunker;
    private final RecursiveChunker  recursiveChunker;
    private final SemanticChunker   semanticChunker;

    /**
     * Chunk text using the configured strategy (or an explicit override).
     *
     * @param text              Source text to chunk
     * @param strategyOverride  Optional strategy name override: fixed | recursive | semantic
     * @param chunkSizeOverride Optional chunk-size override (tokens)
     * @param overlapOverride   Optional overlap override (tokens)
     */
    public List<String> chunk(String text,
                              String strategyOverride,
                              Integer chunkSizeOverride,
                              Integer overlapOverride) {

        AppProperties.Chunking cfg = props.getChunking();

        if (text == null || text.isBlank()) return List.of();

        String  strategy  = strategyOverride  != null ? strategyOverride  : cfg.getStrategy();
        int     chunkSize = chunkSizeOverride != null ? chunkSizeOverride : cfg.getChunkSize();
        int     overlap   = overlapOverride   != null ? overlapOverride   : cfg.getChunkOverlap();

        if (chunkSize <= 0 || chunkSize > cfg.getMaxChunkSize()) {
            throw new InvalidFileException("chunkSize must be between 1 and " + cfg.getMaxChunkSize());
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new InvalidFileException("chunkOverlap must be between 0 and chunkSize - 1");
        }

        log.info("Chunking text ({} chars) with strategy={}, chunkSize={}, overlap={}",
                text.length(), strategy, chunkSize, overlap);

        List<String> chunks = switch (strategy.toLowerCase()) {
            case "fixed"     -> fixedSizeChunker.chunk(text, chunkSize, overlap);
            case "semantic"  -> semanticChunker.chunk(text, chunkSize, overlap);
            case "recursive" -> recursiveChunker.chunk(text, chunkSize, overlap);
            default -> throw new InvalidFileException("Unknown chunking strategy: " + strategy);
        };

        // Keep short, non-blank documents searchable.
        chunks = chunks.stream()
                .filter(c -> !c.isBlank())
                .map(c -> c.length() > cfg.getMaxChunkSize()
                        ? c.substring(0, cfg.getMaxChunkSize()) : c)
                .toList();

        log.info("Chunking produced {} chunks", chunks.size());
        return chunks;
    }

    /** Convenience overload using configured defaults. */
    public List<String> chunk(String text) {
        return chunk(text, null, null, null);
    }
}
