package com.ragapp.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base exception for all RAG application errors.
 * Carries an HTTP status and an error code for structured error responses.
 */
@Getter
public class RagException extends RuntimeException {

    private final HttpStatus status;
    private final String     errorCode;

    public RagException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status    = status;
        this.errorCode = errorCode;
    }

    public RagException(String message, HttpStatus status, String errorCode, Throwable cause) {
        super(message, cause);
        this.status    = status;
        this.errorCode = errorCode;
    }
}
