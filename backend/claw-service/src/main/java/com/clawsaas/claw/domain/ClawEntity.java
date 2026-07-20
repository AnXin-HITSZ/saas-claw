package com.clawsaas.claw.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "claws")
public class ClawEntity {
    @Id
    private String id;
    @Column(nullable = false)
    private String ownerUserId;
    @Column(nullable = false)
    private String name;
    @Column(length = 2048)
    private String description;
    @Column(nullable = false)
    private String status;
    private String defaultAgentId;
    @Column(nullable = false)
    private boolean feishuEnabled;
    private String feishuAccountId;
    private String feishuPeerKind;
    private String feishuPeerId;
    @Column(length = 2048)
    private String feishuComment;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDefaultAgentId() { return defaultAgentId; }
    public void setDefaultAgentId(String defaultAgentId) { this.defaultAgentId = defaultAgentId; }
    public boolean isFeishuEnabled() { return feishuEnabled; }
    public void setFeishuEnabled(boolean feishuEnabled) { this.feishuEnabled = feishuEnabled; }
    public String getFeishuAccountId() { return feishuAccountId; }
    public void setFeishuAccountId(String feishuAccountId) { this.feishuAccountId = feishuAccountId; }
    public String getFeishuPeerKind() { return feishuPeerKind; }
    public void setFeishuPeerKind(String feishuPeerKind) { this.feishuPeerKind = feishuPeerKind; }
    public String getFeishuPeerId() { return feishuPeerId; }
    public void setFeishuPeerId(String feishuPeerId) { this.feishuPeerId = feishuPeerId; }
    public String getFeishuComment() { return feishuComment; }
    public void setFeishuComment(String feishuComment) { this.feishuComment = feishuComment; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
