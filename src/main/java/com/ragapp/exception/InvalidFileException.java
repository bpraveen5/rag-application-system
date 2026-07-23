package com.ragapp.exception;

import org.springframework.http.HttpStatus;

public class InvalidFileException extends RagException {
    public InvalidFileException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "INVALID_FILE");
    }
}
