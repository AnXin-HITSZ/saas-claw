package com.clawsaas.claw.repository;

import com.clawsaas.claw.domain.ConversationMessageEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, String> {
    List<ConversationMessageEntity> findByConversationIdAndOwnerUserIdOrderByCreatedAtAsc(String conversationId, String ownerUserId);

    List<ConversationMessageEntity> findByConversationIdOrderByCreatedAtAsc(String conversationId);
}
