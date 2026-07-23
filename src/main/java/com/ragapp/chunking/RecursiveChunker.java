package com.ragapp.chunking;

import com.ragapp.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive character-based chunking that honours natural text boundaries.
 * Tries each separator in order, splitting only when the segment is too large.
 *
 * <p>Mirrors LangChain's RecursiveCharacterTextSplitter behaviour.
 */
@Component
@RequiredArgsConstructor
public class RecursiveChunker implements Chunker {

    private final AppProperties props;

    @Override
    public List<String> chunk(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) return List.of();
        List<String> separators = props.getChunking().getSeparators();
        List<String> chunks = splitRecursively(text.strip(), chunkSize, overlap, separators, 0);
        return mergeSmallChunks(chunks, chunkSize, overlap);
    }

    private List<String> splitRecursively(String text, int chunkSize, int overlap,
                                           List<String> separators, int depth) {
        if (text.length() <= chunkSize) return List.of(text);
        if (depth >= separators.size()) {
            // Hard split when no separator works
            List<String> hardChunks = new ArrayList<>();
            for (int i = 0; i < text.length(); i += Math.max(1, chunkSize - overlap)) {
                hardChunks.add(text.substring(i, Math.min(i + chunkSize, text.length())));
                if (i + chunkSize >= text.length()) break;
            }
            return hardChunks;
        }

        String sep = separators.get(depth);
        List<String> result = new ArrayList<>();

        if (sep.isEmpty()) {
            // Character-level split
            return splitRecursively(text, chunkSize, overlap, separators, depth + 1);
        }

        String[] parts = text.split(java.util.regex.Pattern.quote(sep), -1);
        for (String part : parts) {
            String stripped = part.strip();
            if (stripped.isEmpty()) continue;
            if (stripped.length() <= chunkSize) {
                result.add(stripped);
            } else {
                result.addAll(splitRecursively(stripped, chunkSize, overlap, separators, depth + 1));
            }
        }
        return result;
    }

    private List<String> mergeSmallChunks(List<String> chunks, int chunkSize, int overlap) {
        List<String> merged  = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String chunk : chunks) {
            if (current.length() + chunk.length() + 1 <= chunkSize) {
                if (!current.isEmpty()) current.append(" ");
                current.append(chunk);
            } else {
                if (!current.isEmpty()) merged.add(current.toString().strip());

                // Carry-over overlap from the previous chunk
                if (overlap > 0 && !current.isEmpty()) {
                    String prev = current.toString();
                    int overlapStart = Math.max(0, prev.length() - overlap);
                    current = new StringBuilder(prev.substring(overlapStart));
                    if (!current.isEmpty()) current.append(" ");
                    current.append(chunk);
                } else {
                    current = new StringBuilder(chunk);
                }
            }
        }
        if (!current.isEmpty()) merged.add(current.toString().strip());
        return merged;
    }
}
