package com.ragapp.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class DocumentNotFoundException extends RagException {

    public DocumentNotFoundException(UUID id) {
        super("Document not found: " + id, HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND");
    }

    public DocumentNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND");
    }
}
