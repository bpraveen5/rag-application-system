package com.ragapp.service;

import com.ragapp.ai.llm.LlmClient;
import com.ragapp.ai.retriever.RetrieverService;
import com.ragapp.dto.ChatDto;
import com.ragapp.entity.Conversation;
import com.ragapp.entity.Message;
import com.ragapp.exception.ConversationNotFoundException;
import com.ragapp.repository.ConversationRepository;
import com.ragapp.repository.MessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the full RAG pipeline per chat request:
 * Question → Embed → Retrieve → Prompt → LLM → Persist → Return.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final RetrieverService      retrieverService;
    private final LlmClient             llmClient;
    private final ConversationRepository conversationRepository;
    private final MessageRepository     messageRepository;
    private final AuditService          auditService;
    private final MeterRegistry         meterRegistry;

    private static final int MAX_HISTORY = 20;

    // ─── Synchronous Chat ─────────────────────────────────────────────────────

    @Transactional
    public ChatDto.ChatResponse chat(ChatDto.ChatRequest request, UUID userId, String tenantId) {
        long totalStart = System.currentTimeMillis();

        // 1. Resolve / create conversation
        Conversation conversation = resolveConversation(request.conversationId(), userId, tenantId);

        // 2. Load history
        List<ChatDto.MessageResponse> history = loadHistory(conversation.getId());

        // 3. Retrieve context
        long retrievalStart = System.currentTimeMillis();
        List<ChatDto.RetrievedChunk> chunks = retrieverService.retrieve(
                request.question(), null,
                request.topK(), request.minSimilarity(),
                request.documentIds());
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        // 4. Call LLM
        long llmStart = System.currentTimeMillis();
        LlmClient.LlmResponse llmResponse = llmClient.chat(
                request.question(), chunks, history,
                request.model(), request.temperature());
        long llmMs = System.currentTimeMillis() - llmStart;

        // 5. Persist user message + assistant message
        Message userMsg = persistMessage(conversation, Message.MessageRole.USER,
                request.question(), null, null);
        Message assistantMsg = persistMessage(conversation, Message.MessageRole.ASSISTANT,
                llmResponse.content(), chunks.isEmpty() ? null : chunks.get(0).similarityScore(),
                serializeSources(chunks));

        // Auto-title conversation on first exchange
        if (conversation.getTitle() == null) {
            String title = request.question().length() > 60
                    ? request.question().substring(0, 60) + "…"
                    : request.question();
            conversationRepository.updateTitle(conversation.getId(), title);
        }

        meterRegistry.timer("rag.chat.total.latency").record(
                System.currentTimeMillis() - totalStart, java.util.concurrent.TimeUnit.MILLISECONDS);

        log.info("Chat completed: convId={}, chunks={}, retrievalMs={}, llmMs={}, inputTok={}, outputTok={}",
                conversation.getId(), chunks.size(), retrievalMs, llmMs,
                llmResponse.inputTokens(), llmResponse.outputTokens());

        return new ChatDto.ChatResponse(
                conversation.getId(), assistantMsg.getId(),
                llmResponse.content(), chunks, chunks.size(),
                retrievalMs, llmMs,
                llmResponse.inputTokens(), llmResponse.outputTokens(),
                llmResponse.model());
    }

    // ─── Streaming Chat ───────────────────────────────────────────────────────

    @Transactional
    public Flux<String> stream(ChatDto.ChatRequest request, UUID userId, String tenantId) {
        Conversation conversation = resolveConversation(request.conversationId(), userId, tenantId);
        List<ChatDto.MessageResponse> history = loadHistory(conversation.getId());

        List<ChatDto.RetrievedChunk> chunks = retrieverService.retrieve(
                request.question(), null,
                request.topK(), request.minSimilarity(),
                request.documentIds());

        persistMessage(conversation, Message.MessageRole.USER, request.question(), null, null);

        return llmClient.stream(request.question(), chunks, history,
                request.model(), request.temperature());
    }

    // ─── Semantic Search ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ChatDto.SearchResponse search(ChatDto.SearchRequest request) {
        long start = System.currentTimeMillis();
        List<ChatDto.RetrievedChunk> results = retrieverService.retrieve(
                request.query(), request.filter(),
                request.topK(), request.minSimilarity(),
                request.documentIds());
        long latency = System.currentTimeMillis() - start;
        return new ChatDto.SearchResponse(request.query(), results, results.size(), latency);
    }

    // ─── Conversation History ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ChatDto.ConversationResponse getConversation(UUID conversationId, UUID userId) {
        Conversation conv = conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        List<ChatDto.MessageResponse> messages = messageRepository
                .findByConversationIdOrderByTimestampAsc(conversationId).stream()
                .map(m -> new ChatDto.MessageResponse(m.getId(), m.getRole().name(),
                        m.getContent(), Collections.emptyList(), m.getTimestamp()))
                .toList();

        return new ChatDto.ConversationResponse(conv.getId(), conv.getTitle(), messages, conv.getCreatedAt());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Conversation resolveConversation(UUID conversationId, UUID userId, String tenantId) {
        if (conversationId != null) {
            return conversationRepository.findByIdAndUserId(conversationId, userId)
                    .orElseThrow(() -> new ConversationNotFoundException(conversationId));
        }
        Conversation newConv = Conversation.builder()
                .userId(userId)
                .tenantId(tenantId)
                .build();
        return conversationRepository.save(newConv);
    }

    private List<ChatDto.MessageResponse> loadHistory(UUID conversationId) {
        return messageRepository.findRecentByConversationId(
                        conversationId, PageRequest.of(0, MAX_HISTORY)).stream()
                .sorted(java.util.Comparator.comparing(Message::getTimestamp))
                .map(m -> new ChatDto.MessageResponse(m.getId(), m.getRole().name(),
                        m.getContent(), Collections.emptyList(), m.getTimestamp()))
                .toList();
    }

    private Message persistMessage(Conversation conversation, Message.MessageRole role,
                                    String content, Double score, String sources) {
        Message msg = Message.builder()
                .conversation(conversation)
                .role(role)
                .content(content)
                .tokenCount(content != null ? content.length() / 4 : 0)
                .retrievalScore(score)
                .sources(sources)
                .build();
        return messageRepository.save(msg);
    }

    private String serializeSources(List<ChatDto.RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < chunks.size(); i++) {
            ChatDto.RetrievedChunk c = chunks.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"documentId\":\"").append(c.documentId())
              .append("\",\"score\":").append(c.similarityScore()).append("}");
        }
        sb.append("]");
        return sb.toString();
    }
}
