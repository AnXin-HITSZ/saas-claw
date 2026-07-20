package com.clawsaas.claw.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "conversation_messages",
        indexes = {
                @Index(name = "idx_conversation_messages_conv_created", columnList = "conversationId,createdAt"),
                @Index(name = "idx_conversation_messages_conv_agent_created", columnList = "conversationId,agentInstanceId,createdAt")
        }
)
public class ConversationMessageEntity {
    @Id
    private String id;
    @Column(nullable = false)
    private String conversationId;
    @Column(nullable = false)
    private String ownerUserId;
    @Column(nullable = false)
    private String clawId;
    private String agentInstanceId;
    private String agentId;
    private String agentKey;
    private String roleKey;
    private String provider;
    private String model;
    @Column(nullable = false)
    private String role;
    @Lob
    private String content;
    @Column(nullable = false)
    private OffsetDateTime createdAt;

    // ---- Multi-agent display model (Task 1) ----
    @Column(length = 32)
    private String messageType;
    private String parentMessageId;
    @Lob
    private String metadataJson;
    @Column(nullable = false)
    private boolean visibleInThread = true;
    @Column(nullable = false)
    private int sortOrder;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getClawId() { return clawId; }
    public void setClawId(String clawId) { this.clawId = clawId; }
    public String getAgentInstanceId() { return agentInstanceId; }
    public void setAgentInstanceId(String agentInstanceId) { this.agentInstanceId = agentInstanceId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getAgentKey() { return agentKey; }
    public void setAgentKey(String agentKey) { this.agentKey = agentKey; }
    public String getRoleKey() { return roleKey; }
    public void setRoleKey(String roleKey) { this.roleKey = roleKey; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public String getParentMessageId() { return parentMessageId; }
    public void setParentMessageId(String parentMessageId) { this.parentMessageId = parentMessageId; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public boolean isVisibleInThread() { return visibleInThread; }
    public void setVisibleInThread(boolean visibleInThread) { this.visibleInThread = visibleInThread; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
