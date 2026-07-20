package com.clawsaas.claw.service;

import com.clawsaas.claw.domain.ConversationEntity;
import com.clawsaas.claw.domain.ConversationMessageEntity;
import java.util.List;
import org.springframework.security.core.Authentication;

public interface ConversationService {

    ConversationEntity create(String clawId, String title, Authentication authentication);

    ConversationEntity getOrCreate(String conversationId, String clawId, Authentication authentication);

    ConversationEntity get(String conversationId, Authentication authentication);

    List<ConversationEntity> listByClaw(String clawId, Authentication authentication);

    List<ConversationEntity> listByUser(Authentication authentication);

    ConversationMessageEntity saveMessage(
            String conversationId, String ownerUserId, String clawId,
            String agentInstanceId, String agentId, String agentKey,
            String roleKey, String provider, String model,
            String role, String content);

    ConversationMessageEntity saveMessage(
            String conversationId, String ownerUserId, String clawId,
            String agentInstanceId, String agentId, String agentKey,
            String roleKey, String provider, String model,
            String role, String content,
            String messageType, String parentMessageId, String metadataJson,
            boolean visibleInThread, int sortOrder);

    List<ConversationMessageEntity> getMessages(String conversationId, Authentication authentication);

    ConversationEntity getConversationInternal(String conversationId);

    List<ConversationMessageEntity> getMessagesInternal(String conversationId);
}
