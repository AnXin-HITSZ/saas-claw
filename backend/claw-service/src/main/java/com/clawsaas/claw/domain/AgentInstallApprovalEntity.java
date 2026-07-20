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
        name = "agent_install_approvals",
        indexes = {
                @Index(name = "idx_agent_install_approvals_claw_status_created", columnList = "clawId,status,createdAt"),
                @Index(name = "idx_agent_install_approvals_owner_status_created", columnList = "ownerUserId,status,createdAt"),
                @Index(name = "idx_agent_install_approvals_requester_created", columnList = "requestingAgentInstanceId,createdAt")
        }
)
public class AgentInstallApprovalEntity {
    @Id
    private String id;
    @Column(nullable = false)
    private String approvalType;
    @Column(nullable = false)
    private String clawId;
    @Column(nullable = false)
    private String ownerUserId;
    private String requestingAgentInstanceId;
    @Column(nullable = false)
    private String packageId;
    @Column(nullable = false)
    private String packageVersionId;
    @Lob
    private String reason;
    @Column(nullable = false)
    private String status;
    private OffsetDateTime expiresAt;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    private OffsetDateTime resolvedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getApprovalType() { return approvalType; }
    public void setApprovalType(String approvalType) { this.approvalType = approvalType; }
    public String getClawId() { return clawId; }
    public void setClawId(String clawId) { this.clawId = clawId; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getRequestingAgentInstanceId() { return requestingAgentInstanceId; }
    public void setRequestingAgentInstanceId(String requestingAgentInstanceId) { this.requestingAgentInstanceId = requestingAgentInstanceId; }
    public String getPackageId() { return packageId; }
    public void setPackageId(String packageId) { this.packageId = packageId; }
    public String getPackageVersionId() { return packageVersionId; }
    public void setPackageVersionId(String packageVersionId) { this.packageVersionId = packageVersionId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
}
