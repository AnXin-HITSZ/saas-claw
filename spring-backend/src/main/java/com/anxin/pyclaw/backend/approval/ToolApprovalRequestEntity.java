package com.anxin.pyclaw.backend.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "tool_approval_requests",
        indexes = {
                @Index(name = "idx_tool_approval_owner_created", columnList = "ownerUserId,createdAt"),
                @Index(name = "idx_tool_approval_claw_created", columnList = "clawId,createdAt"),
                @Index(name = "idx_tool_approval_session", columnList = "sessionId"),
                @Index(name = "idx_tool_approval_status_expires", columnList = "status,expiresAt")
        }
)
public class ToolApprovalRequestEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String ownerUserId;

    @Column(nullable = false, length = 64)
    private String clawId;

    @Column(length = 255)
    private String clawName;

    @Column(nullable = false, length = 128)
    private String sessionId;

    @Column(length = 64)
    private String agentId;

    @Column(length = 128)
    private String agentKey;

    @Column(length = 128)
    private String roleKey;

    @Column(nullable = false, length = 128)
    private String toolCallId;

    @Column(nullable = false, length = 128)
    private String toolName;

    @Column(nullable = false, length = 32)
    private String risk;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ToolApprovalStatus status;

    @Column(length = 1024)
    private String intentSummary;

    @Lob
    private String argumentsPreview;

    @Column(nullable = false, length = 255)
    private String pendingStateKey;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    @Column(length = 64)
    private String resolvedBy;

    private OffsetDateTime resolvedAt;

    @Column(length = 1024)
    private String rejectReason;

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
    public String getClawName() { return clawName; }
    public void setClawName(String clawName) { this.clawName = clawName; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getAgentKey() { return agentKey; }
    public void setAgentKey(String agentKey) { this.agentKey = agentKey; }
    public String getRoleKey() { return roleKey; }
    public void setRoleKey(String roleKey) { this.roleKey = roleKey; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getRisk() { return risk; }
    public void setRisk(String risk) { this.risk = risk; }
    public ToolApprovalStatus getStatus() { return status; }
    public void setStatus(ToolApprovalStatus status) { this.status = status; }
    public String getIntentSummary() { return intentSummary; }
    public void setIntentSummary(String intentSummary) { this.intentSummary = intentSummary; }
    public String getArgumentsPreview() { return argumentsPreview; }
    public void setArgumentsPreview(String argumentsPreview) { this.argumentsPreview = argumentsPreview; }
    public String getPendingStateKey() { return pendingStateKey; }
    public void setPendingStateKey(String pendingStateKey) { this.pendingStateKey = pendingStateKey; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
