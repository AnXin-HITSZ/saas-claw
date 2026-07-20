package com.clawsaas.claw.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "conversations",
        indexes = {
                @Index(name = "idx_conversations_owner_updated", columnList = "ownerUserId,updatedAt"),
                @Index(name = "idx_conversations_claw_updated", columnList = "clawId,updatedAt")
        }
)
public class ConversationEntity {
    @Id
    private String id;
    @Column(nullable = false)
    private String ownerUserId;
    @Column(nullable = false)
    private String clawId;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String status;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getClawId() { return clawId; }
    public void setClawId(String clawId) { this.clawId = clawId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
