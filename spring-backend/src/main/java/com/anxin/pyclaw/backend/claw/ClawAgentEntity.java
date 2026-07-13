package com.anxin.pyclaw.backend.claw;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "claw_agents")
public class ClawAgentEntity {
    @Id
    private String id;
    @Column(nullable = false)
    private String clawId;
    @Column(nullable = false)
    private String agentId;
    @Column(nullable = false)
    private String roleKey;
    @Column(nullable = false)
    private String displayName;
    @Lob
    private String mentionAliasesJson;
    @Lob
    private String commandPrefixesJson;
    @Column(nullable = false)
    private boolean defaultRole;
    @Column(nullable = false)
    private boolean enabled;
    @Column(nullable = false)
    private int sortOrder;
    private String routeBindingId;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getClawId() { return clawId; }
    public void setClawId(String clawId) { this.clawId = clawId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getRoleKey() { return roleKey; }
    public void setRoleKey(String roleKey) { this.roleKey = roleKey; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getMentionAliasesJson() { return mentionAliasesJson; }
    public void setMentionAliasesJson(String mentionAliasesJson) { this.mentionAliasesJson = mentionAliasesJson; }
    public String getCommandPrefixesJson() { return commandPrefixesJson; }
    public void setCommandPrefixesJson(String commandPrefixesJson) { this.commandPrefixesJson = commandPrefixesJson; }
    public boolean isDefaultRole() { return defaultRole; }
    public void setDefaultRole(boolean defaultRole) { this.defaultRole = defaultRole; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getRouteBindingId() { return routeBindingId; }
    public void setRouteBindingId(String routeBindingId) { this.routeBindingId = routeBindingId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
