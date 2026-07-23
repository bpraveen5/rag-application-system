package com.ragapp.chunking;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size character-based chunking with configurable overlap.
 * Splits text into equal-sized windows sliding by (chunkSize - overlap) chars.
 */
@Component
public class FixedSizeChunker implements Chunker {

    @Override
    public List<String> chunk(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) return List.of();
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be > 0");
        if (overlap < 0 || overlap >= chunkSize) overlap = 0;

        List<String> chunks = new ArrayList<>();
        int step = chunkSize - overlap;
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end).strip();
            if (!chunk.isBlank()) chunks.add(chunk);
            if (end == text.length()) break;
            start += step;
        }
        return chunks;
    }
}
