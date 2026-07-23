package com.ragapp.repository;

import com.ragapp.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversationIdOrderByTimestampAsc(UUID conversationId);

    @Query("SELECT m FROM Message m WHERE m.conversation.id = :conversationId " +
           "ORDER BY m.timestamp DESC")
    List<Message> findRecentByConversationId(@Param("conversationId") UUID conversationId, Pageable pageable);

    long countByConversationId(UUID conversationId);

    void deleteByConversationId(UUID conversationId);
}
