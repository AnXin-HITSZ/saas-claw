package com.clawsaas.agentmarketplace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "agent_packages",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_packages_owner_key", columnNames = {"ownerUserId", "packageKey"}),
        indexes = {
                @Index(name = "idx_agent_packages_visibility_updated", columnList = "visibility,updatedAt"),
                @Index(name = "idx_agent_packages_owner_updated", columnList = "ownerUserId,updatedAt")
        }
)
public class AgentPackageEntity {
    @Id
    private String id;
    @Column(nullable = false)
    private String packageKey;
    @Column(nullable = false)
    private String ownerUserId;
    @Column(nullable = false)
    private String name;
    @Column(length = 2048)
    private String summary;
    @Lob
    private String description;
    @Column(nullable = false)
    private String visibility;
    private String latestVersionId;
    @Column(nullable = false)
    private long installCount;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPackageKey() { return packageKey; }
    public void setPackageKey(String packageKey) { this.packageKey = packageKey; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getLatestVersionId() { return latestVersionId; }
    public void setLatestVersionId(String latestVersionId) { this.latestVersionId = latestVersionId; }
    public long getInstallCount() { return installCount; }
    public void setInstallCount(long installCount) { this.installCount = installCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
