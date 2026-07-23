package com.ragapp.unit;

import com.ragapp.ai.llm.LlmClient;
import com.ragapp.ai.retriever.RetrieverService;
import com.ragapp.dto.ChatDto;
import com.ragapp.entity.Conversation;
import com.ragapp.entity.Message;
import com.ragapp.repository.ConversationRepository;
import com.ragapp.repository.MessageRepository;
import com.ragapp.service.AuditService;
import com.ragapp.service.ChatService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatService Unit Tests")
class ChatServiceTest {

    @Mock RetrieverService      retrieverService;
    @Mock LlmClient             llmClient;
    @Mock ConversationRepository conversationRepository;
    @Mock MessageRepository     messageRepository;
    @Mock AuditService          auditService;

    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    ChatService   chatService;

    private UUID userId;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(retrieverService, llmClient, conversationRepository,
                messageRepository, auditService, meterRegistry);
        userId       = UUID.randomUUID();
        conversation = Conversation.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .build();
    }

    @Test
    @DisplayName("chat() creates new conversation when conversationId is null")
    void chat_creates_new_conversation() {
        ChatDto.ChatRequest request = new ChatDto.ChatRequest(
                "What is RAG?", null, null, null, null, false, null, null);

        when(conversationRepository.save(any(Conversation.class))).thenReturn(conversation);
        when(messageRepository.findRecentByConversationId(any(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(retrieverService.retrieve(anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(llmClient.chat(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(new LlmClient.LlmResponse("RAG stands for...", 100, 50, "llama3.1:8b"));

        Message savedMsg = Message.builder()
                .id(UUID.randomUUID())
                .role(Message.MessageRole.ASSISTANT)
                .content("RAG stands for...")
                .timestamp(Instant.now())
                .build();
        when(messageRepository.save(any(Message.class))).thenReturn(savedMsg);
        doNothing().when(conversationRepository).updateTitle(any(), anyString());

        ChatDto.ChatResponse response = chatService.chat(request, userId, null);

        assertThat(response).isNotNull();
        assertThat(response.conversationId()).isEqualTo(conversation.getId());
        assertThat(response.answer()).isEqualTo("RAG stands for...");
        assertThat(response.model()).isEqualTo("llama3.1:8b");
        verify(conversationRepository).save(any(Conversation.class));
    }

    @Test
    @DisplayName("chat() uses existing conversation when conversationId provided")
    void chat_uses_existing_conversation() {
        UUID convId = conversation.getId();
        ChatDto.ChatRequest request = new ChatDto.ChatRequest(
                "Follow-up question", convId, null, null, null, false, null, null);

        when(conversationRepository.findByIdAndUserId(convId, userId))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findRecentByConversationId(any(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(retrieverService.retrieve(anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(llmClient.chat(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(new LlmClient.LlmResponse("Answer", 50, 30, "llama3.1:8b"));

        Message savedMsg = Message.builder()
                .id(UUID.randomUUID())
                .role(Message.MessageRole.ASSISTANT)
                .content("Answer")
                .timestamp(Instant.now())
                .build();
        when(messageRepository.save(any(Message.class))).thenReturn(savedMsg);

        ChatDto.ChatResponse response = chatService.chat(request, userId, null);

        assertThat(response.conversationId()).isEqualTo(convId);
        verify(conversationRepository, never()).save(any()); // no new conversation
    }

    @Test
    @DisplayName("search() returns ranked chunks")
    void search_returns_results() {
        ChatDto.SearchRequest request = new ChatDto.SearchRequest(
                "machine learning", 5, 0.7, null, null);

        List<ChatDto.RetrievedChunk> chunks = List.of(
                new ChatDto.RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(),
                        "doc.pdf", "ML is...", 0.92, 0, null, Collections.emptyMap()),
                new ChatDto.RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(),
                        "doc2.pdf", "Deep learning...", 0.85, 1, null, Collections.emptyMap())
        );

        when(retrieverService.retrieve(anyString(), any(), any(), any(), any()))
                .thenReturn(chunks);

        ChatDto.SearchResponse response = chatService.search(request);

        assertThat(response).isNotNull();
        assertThat(response.query()).isEqualTo("machine learning");
        assertThat(response.results()).hasSize(2);
        assertThat(response.totalResults()).isEqualTo(2);
        assertThat(response.results().get(0).similarityScore()).isCloseTo(0.92, within(0.001));
    }

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }
}
