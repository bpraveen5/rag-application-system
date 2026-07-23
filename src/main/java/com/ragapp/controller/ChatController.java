package com.ragapp.controller;

import com.ragapp.dto.ChatDto;
import com.ragapp.security.RagUserPrincipal;
import com.ragapp.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping
@RequiredArgsConstructor
@Tag(name = "Chat & Search", description = "RAG chat, semantic search, and conversation history")
public class ChatController {

    private final ChatService chatService;

    /**
     * POST /chat
     * Synchronous RAG chat. Set {@code stream=true} to get SSE streaming.
     */
    @PostMapping("/chat")
    @Operation(summary = "Send a question and get an AI-powered answer grounded in your documents")
    public ResponseEntity<ChatDto.ChatResponse> chat(
            @Valid @RequestBody ChatDto.ChatRequest request,
            @AuthenticationPrincipal RagUserPrincipal principal,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {

        if (Boolean.TRUE.equals(request.stream())) {
            // Return 400 — caller should use /chat/stream for SSE
            return ResponseEntity.badRequest().build();
        }

        ChatDto.ChatResponse response = chatService.chat(request, principal.getId(), tenantId);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /chat/stream
     * Server-Sent Events streaming for real-time token delivery.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream an AI response token-by-token via Server-Sent Events")
    public Flux<String> streamChat(
            @Valid @RequestBody ChatDto.ChatRequest request,
            @AuthenticationPrincipal RagUserPrincipal principal,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {

        return chatService.stream(request, principal.getId(), tenantId);
    }

    /**
     * POST /search
     * Semantic similarity search over indexed documents.
     */
    @PostMapping("/search")
    @Operation(summary = "Semantic search over indexed documents — returns ranked chunks")
    public ResponseEntity<ChatDto.SearchResponse> search(
            @Valid @RequestBody ChatDto.SearchRequest request,
            @AuthenticationPrincipal RagUserPrincipal principal) {

        ChatDto.SearchResponse response = chatService.search(request);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /conversations/{id}
     * Retrieve a full conversation with all messages.
     */
    @GetMapping("/conversations/{id}")
    @Operation(summary = "Get conversation history by ID")
    public ResponseEntity<ChatDto.ConversationResponse> getConversation(
            @PathVariable UUID id,
            @AuthenticationPrincipal RagUserPrincipal principal) {

        return ResponseEntity.ok(chatService.getConversation(id, principal.getId()));
    }
}
