package com.ragapp.unit;

import com.ragapp.ai.embedding.EmbeddingService;
import com.ragapp.exception.EmbeddingException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingService Unit Tests")
class EmbeddingServiceTest {

    @Mock EmbeddingModel embeddingModel;
    @Mock Environment   environment;

    MeterRegistry    meterRegistry;
    EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        meterRegistry    = new SimpleMeterRegistry();
        embeddingService = new EmbeddingService(embeddingModel, meterRegistry, environment);
    }

    @Test
    @DisplayName("embed() returns float array from model response")
    void embed_returns_float_array() {
        float[] fakeEmbedding = {0.1f, 0.2f, 0.3f};
        Embedding embedding = new Embedding(fakeEmbedding, 0);
        EmbeddingResponse response = new EmbeddingResponse(List.of(embedding));
        when(embeddingModel.call(any(EmbeddingRequest.class))).thenReturn(response);

        float[] result = embeddingService.embed("Hello world");

        assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
        verify(embeddingModel, times(1)).call(any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("embed() throws EmbeddingException on blank input")
    void embed_throws_on_blank_input() {
        assertThatThrownBy(() -> embeddingService.embed(""))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("empty");

        assertThatThrownBy(() -> embeddingService.embed(null))
                .isInstanceOf(EmbeddingException.class);
    }

    @Test
    @DisplayName("embed() wraps API exceptions in EmbeddingException")
    void embed_wraps_api_exceptions() {
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenThrow(new RuntimeException("API timeout"));

        assertThatThrownBy(() -> embeddingService.embed("test text"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("API timeout");
    }

    @Test
    @DisplayName("embedBatch() returns empty list for empty input")
    void embedBatch_returns_empty_for_empty_input() {
        assertThat(embeddingService.embedBatch(List.of())).isEmpty();
        assertThat(embeddingService.embedBatch(null)).isEmpty();
        verifyNoInteractions(embeddingModel);
    }

    @Test
    @DisplayName("toVectorLiteral() produces correct PostgreSQL format")
    void toVectorLiteral_produces_correct_format() {
        float[] embedding = {1.0f, -0.5f, 0.25f};
        String literal = EmbeddingService.toVectorLiteral(embedding);
        assertThat(literal).startsWith("[").endsWith("]");
        assertThat(literal).contains("1.0", "-0.5", "0.25");
    }

    @Test
    @DisplayName("cosineSimilarity() returns 1.0 for identical vectors")
    void cosineSimilarity_identical_vectors() {
        float[] v = {1.0f, 0.0f, 0.0f};
        double similarity = EmbeddingService.cosineSimilarity(v, v);
        assertThat(similarity).isCloseTo(1.0, within(0.0001));
    }

    @Test
    @DisplayName("cosineSimilarity() returns 0.0 for orthogonal vectors")
    void cosineSimilarity_orthogonal_vectors() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};
        double similarity = EmbeddingService.cosineSimilarity(a, b);
        assertThat(similarity).isCloseTo(0.0, within(0.0001));
    }

    @Test
    @DisplayName("cosineSimilarity() throws for mismatched dimensions")
    void cosineSimilarity_throws_for_dimension_mismatch() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f, 0.5f};
        assertThatThrownBy(() -> EmbeddingService.cosineSimilarity(a, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mismatch");
    }

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }
}
