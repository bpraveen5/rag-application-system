package com.ragapp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A single message within a {@link Conversation}.
 * Roles follow the standard chat convention used by the LLM API: USER, ASSISTANT, SYSTEM.
 */
@Entity
@Table(name = "messages",
        indexes = {
            @Index(name = "idx_messages_conversation_id", columnList = "conversation_id"),
            @Index(name = "idx_messages_timestamp",       columnList = "timestamp")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "token_count")
    private Integer tokenCount;

    /** The similarity score of retrieved chunks used to generate this answer (ASSISTANT role). */
    @Column(name = "retrieval_score")
    private Double retrievalScore;

    /** JSON snapshot of the retrieved sources used for this response. */
    @Column(name = "sources", columnDefinition = "TEXT")
    private String sources;

    @CreationTimestamp
    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    public enum MessageRole {
        USER, ASSISTANT, SYSTEM
    }
}
