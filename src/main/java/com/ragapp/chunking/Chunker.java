package com.ragapp.chunking;

import java.util.List;

/** Strategy interface for text chunking implementations. */
public interface Chunker {
    List<String> chunk(String text, int chunkSize, int overlap);
}
