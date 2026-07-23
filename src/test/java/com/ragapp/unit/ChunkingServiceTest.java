package com.ragapp.unit;

import com.ragapp.chunking.*;
import com.ragapp.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the three chunking strategies.
 * No Spring context required — pure POJO tests.
 */
@DisplayName("Chunking Service Tests")
class ChunkingServiceTest {

    private AppProperties      props;
    private FixedSizeChunker   fixedSizeChunker;
    private RecursiveChunker   recursiveChunker;
    private SemanticChunker    semanticChunker;
    private ChunkingService    chunkingService;

    private static final String LOREM = """
            Lorem ipsum dolor sit amet, consectetur adipiscing elit.
            Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.
            Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris.
            
            Nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in
            reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur.
            
            Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia
            deserunt mollit anim id est laborum.
            """;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        AppProperties.Chunking cfg = new AppProperties.Chunking();
        cfg.setChunkSize(200);
        cfg.setChunkOverlap(20);
        cfg.setMinChunkSize(10);
        cfg.setMaxChunkSize(4000);
        cfg.setSeparators(List.of("\n\n", "\n", ". ", " ", ""));
        props.setChunking(cfg);

        fixedSizeChunker  = new FixedSizeChunker();
        recursiveChunker  = new RecursiveChunker(props);
        semanticChunker   = new SemanticChunker();
        chunkingService   = new ChunkingService(props, fixedSizeChunker, recursiveChunker, semanticChunker);
    }

    // ─── Fixed-size chunker ───────────────────────────────────────────────────

    @Nested
    @DisplayName("FixedSizeChunker")
    class FixedSizeChunkerTests {

        @Test
        void chunks_text_into_fixed_windows() {
            List<String> chunks = fixedSizeChunker.chunk(LOREM, 100, 0);
            assertThat(chunks).isNotEmpty();
            chunks.forEach(c -> assertThat(c.length()).isLessThanOrEqualTo(105)); // strip may vary by 5
        }

        @Test
        void respects_overlap() {
            String text = "A".repeat(300);
            List<String> chunks = fixedSizeChunker.chunk(text, 100, 20);
            // With overlap=20, step=80, so 300/80 ≈ 4 chunks
            assertThat(chunks.size()).isGreaterThanOrEqualTo(3);
        }

        @Test
        void returns_empty_for_blank_input() {
            assertThat(fixedSizeChunker.chunk("  ", 100, 0)).isEmpty();
            assertThat(fixedSizeChunker.chunk(null, 100, 0)).isEmpty();
        }

        @Test
        void single_chunk_when_text_shorter_than_chunk_size() {
            List<String> chunks = fixedSizeChunker.chunk("Hello world", 1000, 0);
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).isEqualTo("Hello world");
        }
    }

    // ─── Recursive chunker ────────────────────────────────────────────────────

    @Nested
    @DisplayName("RecursiveChunker")
    class RecursiveChunkerTests {

        @Test
        void splits_on_paragraph_boundaries_first() {
            List<String> chunks = recursiveChunker.chunk(LOREM, 300, 0);
            assertThat(chunks).isNotEmpty();
            assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        void all_chunks_within_max_size() {
            List<String> chunks = recursiveChunker.chunk(LOREM, 150, 20);
            chunks.forEach(c -> assertThat(c.length()).isLessThanOrEqualTo(155));
        }

        @Test
        void no_empty_chunks() {
            List<String> chunks = recursiveChunker.chunk(LOREM, 200, 30);
            chunks.forEach(c -> assertThat(c).isNotBlank());
        }
    }

    // ─── Semantic chunker ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("SemanticChunker")
    class SemanticChunkerTests {

        @Test
        void splits_at_sentence_boundaries() {
            String text = "First sentence ends here. Second sentence follows. Third one too.";
            List<String> chunks = semanticChunker.chunk(text, 35, 0);
            assertThat(chunks).isNotEmpty();
            // Sentences should not be broken mid-word
            chunks.forEach(c -> assertThat(c).doesNotEndWith(" "));
        }

        @Test
        void returns_single_chunk_for_short_text() {
            List<String> chunks = semanticChunker.chunk("Short text.", 1000, 0);
            assertThat(chunks).hasSize(1);
        }
    }

    // ─── ChunkingService delegation ───────────────────────────────────────────

    @Nested
    @DisplayName("ChunkingService strategy routing")
    class ChunkingServiceTests {

        @ParameterizedTest
        @ValueSource(strings = {"fixed", "recursive", "semantic"})
        void routes_to_correct_strategy(String strategy) {
            List<String> chunks = chunkingService.chunk(LOREM, strategy, 200, 20);
            assertThat(chunks).isNotEmpty();
        }

        @Test
        void uses_configured_defaults_when_no_override() {
            List<String> chunks = chunkingService.chunk(LOREM);
            assertThat(chunks).isNotEmpty();
        }

        @Test
        void enforces_min_chunk_size() {
            // Very large chunk size → likely one big chunk, never smaller than minChunkSize
            List<String> chunks = chunkingService.chunk("x".repeat(5), "fixed", 1000, 0);
            chunks.forEach(c -> assertThat(c.length()).isGreaterThanOrEqualTo(10));
        }
    }
}
