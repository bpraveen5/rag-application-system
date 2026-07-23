package com.ragapp.exception;

import org.springframework.http.HttpStatus;

public class EmbeddingException extends RagException {
    public EmbeddingException(String message) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE, "EMBEDDING_FAILED");
    }
    public EmbeddingException(String message, Throwable cause) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE, "EMBEDDING_FAILED", cause);
    }
}
