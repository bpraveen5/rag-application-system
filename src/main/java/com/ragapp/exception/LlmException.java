package com.ragapp.exception;

import org.springframework.http.HttpStatus;

public class LlmException extends RagException {
    public LlmException(String message) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE, "LLM_ERROR");
    }
    public LlmException(String message, Throwable cause) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE, "LLM_ERROR", cause);
    }
}
