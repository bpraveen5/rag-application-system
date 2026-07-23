package com.ragapp.chunking;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Semantic-boundary chunker: splits text at sentence boundaries
 * and groups them so each chunk fits within the token budget.
 *
 * <p>Unlike fixed/recursive strategies this respects sentence coherence —
 * a sentence is never split in the middle. Sentence detection uses a simple
 * regex; swap in OpenNLP or Stanford NLP for production-grade accuracy.
 */
@Component
public class SemanticChunker implements Chunker {

    /** Regex that splits after ". ", "! ", "? " and similar end-of-sentence patterns. */
    private static final java.util.regex.Pattern SENTENCE_BOUNDARY =
            java.util.regex.Pattern.compile("(?<=[.!?])\\s+");

    @Override
    public List<String> chunk(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) return List.of();

        String[] sentences = SENTENCE_BOUNDARY.split(text.strip());
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int overlapBuffer = 0;

        for (String sentence : sentences) {
            String s = sentence.strip();
            if (s.isBlank()) continue;

            if (current.length() + s.length() + 1 > chunkSize && !current.isEmpty()) {
                String finished = current.toString().strip();
                chunks.add(finished);

                // Build overlap seed from the tail of the finished chunk
                current = new StringBuilder();
                if (overlap > 0) {
                    int start = Math.max(0, finished.length() - overlap);
                    current.append(finished, start, finished.length());
                    if (!current.isEmpty()) current.append(" ");
                }
            }

            if (!current.isEmpty()) current.append(" ");
            current.append(s);
        }

        if (!current.isEmpty()) chunks.add(current.toString().strip());
        return chunks;
    }
}
