package com.ragapp.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class ConversationNotFoundException extends RagException {
    public ConversationNotFoundException(UUID id) {
        super("Conversation not found: " + id, HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND");
    }
}
