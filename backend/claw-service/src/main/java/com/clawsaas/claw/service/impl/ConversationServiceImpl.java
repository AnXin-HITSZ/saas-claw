package com.clawsaas.claw.service.impl;

import com.clawsaas.claw.domain.AuthenticatedPrincipal;
import com.clawsaas.claw.domain.ClawEntity;
import com.clawsaas.claw.domain.ConversationEntity;
import com.clawsaas.claw.domain.ConversationMessageEntity;
import com.clawsaas.claw.domain.MessageType;
import com.clawsaas.claw.exception.ApiException;
import com.clawsaas.claw.repository.ClawRepository;
import com.clawsaas.claw.repository.ConversationMessageRepository;
import com.clawsaas.claw.repository.ConversationRepository;
import com.clawsaas.claw.service.ConversationService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationServiceImpl implements ConversationService {

    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final ClawRepository claws;

    public ConversationServiceImpl(
            ConversationRepository conversations,
            ConversationMessageRepository messages,
            ClawRepository claws
    ) {
        this.conversations = conversations;
        this.messages = messages;
        this.claws = claws;
    }

    @Override
    @Transactional
    public ConversationEntity create(String clawId, String title, Authentication authentication) {
        String actorId = actorId(authentication);
        boolean admin = isAdmin(authentication);

        ClawEntity claw = claws.findById(clawId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claw not found"));
        if (!admin && !Objects.equals(claw.getOwnerUserId(), actorId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Claw not found");
        }

        OffsetDateTime now = OffsetDateTime.now();
        ConversationEntity entity = new ConversationEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setOwnerUserId(actorId);
        entity.setClawId(clawId);
        entity.setTitle(title != null && !title.isBlank() ? title.trim() : "New Conversation");
        entity.setStatus("active");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return conversations.save(entity);
    }

    @Override
    @Transactional
    public ConversationEntity getOrCreate(String conversationId, String clawId, Authentication authentication) {
        if (conversationId != null && !conversationId.isBlank()) {
            return get(conversationId, authentication);
        }
        return create(clawId, "New Conversation", authentication);
    }

    @Override
    public ConversationEntity get(String conversationId, Authentication authentication) {
        String actorId = actorId(authentication);
        boolean admin = isAdmin(authentication);

        ConversationEntity entity = conversations.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (!admin && !Objects.equals(entity.getOwnerUserId(), actorId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return entity;
    }

    @Override
    public List<ConversationEntity> listByClaw(String clawId, Authentication authentication) {
        String actorId = actorId(authentication);
        return conversations.findByClawIdAndOwnerUserIdOrderByUpdatedAtDesc(clawId, actorId);
    }

    @Override
    public List<ConversationEntity> listByUser(Authentication authentication) {
        String actorId = actorId(authentication);
        return conversations.findByOwnerUserIdOrderByUpdatedAtDesc(actorId);
    }

    @Override
    @Transactional
    public ConversationMessageEntity saveMessage(
            String conversationId,
            String ownerUserId,
            String clawId,
            String agentInstanceId,
            String agentId,
            String agentKey,
            String roleKey,
            String provider,
            String model,
            String role,
            String content
    ) {
        return saveMessage(conversationId, ownerUserId, clawId, agentInstanceId, agentId, agentKey,
                roleKey, provider, model, role, content, null, null, null, true, 0);
    }

    @Override
    @Transactional
    public ConversationMessageEntity saveMessage(
            String conversationId,
            String ownerUserId,
            String clawId,
            String agentInstanceId,
            String agentId,
            String agentKey,
            String roleKey,
            String provider,
            String model,
            String role,
            String content,
            String messageType,
            String parentMessageId,
            String metadataJson,
            boolean visibleInThread,
            int sortOrder
    ) {
        ConversationMessageEntity msg = new ConversationMessageEntity();
        msg.setId(UUID.randomUUID().toString());
        msg.setConversationId(conversationId);
        msg.setOwnerUserId(ownerUserId);
        msg.setClawId(clawId);
        msg.setAgentInstanceId(agentInstanceId);
        msg.setAgentId(agentId);
        msg.setAgentKey(agentKey);
        msg.setRoleKey(roleKey);
        msg.setProvider(provider);
        msg.setModel(model);
        msg.setRole(role);
        msg.setContent(content);
        msg.setCreatedAt(OffsetDateTime.now());
        msg.setMessageType(messageType != null ? messageType : resolveDefaultMessageType(role));
        msg.setParentMessageId(parentMessageId);
        msg.setMetadataJson(metadataJson);
        msg.setVisibleInThread(visibleInThread);
        msg.setSortOrder(sortOrder);

        ConversationMessageEntity saved = messages.save(msg);

        // Update conversation updatedAt
        conversations.findById(conversationId).ifPresent(conv -> {
            conv.setUpdatedAt(saved.getCreatedAt());
            conversations.save(conv);
        });

        return saved;
    }

    private String resolveDefaultMessageType(String role) {
        if ("user".equalsIgnoreCase(role)) {
            return MessageType.USER_MESSAGE.name();
        }
        if ("system".equalsIgnoreCase(role)) {
            return MessageType.SYSTEM_EVENT.name();
        }
        return MessageType.AGENT_MESSAGE.name();
    }

    @Override
    public List<ConversationMessageEntity> getMessages(String conversationId, Authentication authentication) {
        String actorId = actorId(authentication);
        ConversationEntity conv = get(conversationId, authentication);
        return messages.findByConversationIdAndOwnerUserIdOrderByCreatedAtAsc(conv.getId(), actorId);
    }

    @Override
    public ConversationEntity getConversationInternal(String conversationId) {
        return conversations.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    @Override
    public List<ConversationMessageEntity> getMessagesInternal(String conversationId) {
        return messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    private boolean isAdmin(Authentication authentication) {
        Set<String> authorities = authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return authorities.contains("user:manage");
    }

    private String actorId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.userId();
        }
        return null;
    }
}
