package com.ragapp.repository;

import com.ragapp.entity.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Page<Conversation> findByUserIdAndActiveTrue(UUID userId, Pageable pageable);

    Optional<Conversation> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("UPDATE Conversation c SET c.active = false WHERE c.id = :id AND c.userId = :userId")
    int deactivate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE Conversation c SET c.title = :title WHERE c.id = :id")
    void updateTitle(@Param("id") UUID id, @Param("title") String title);
}
