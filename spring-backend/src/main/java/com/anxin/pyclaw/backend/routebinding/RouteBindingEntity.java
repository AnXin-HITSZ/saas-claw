package com.anxin.pyclaw.backend.routebinding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "route_bindings")
public class RouteBindingEntity {
    @Id
    private String id;
    @Column(nullable = false)
    private boolean enabled;
    @Column(nullable = false)
    private int priority;
    private String clawId;
    @Column(nullable = false)
    private String agentId;
    private String channel;
    private String accountId;
    private String peerKind;
    private String peerId;
    private String parentPeerKind;
    private String parentPeerId;
    private String guildId;
    private String teamId;
    @Lob
    private String rolesJson;
    @Lob
    private String senderIdsJson;
    @Lob
    private String mentionAliasesJson;
    @Lob
    private String commandPrefixesJson;
    @Column(nullable = false)
    private String dmScope;
    @Column(length = 2048)
    private String comment;
    private String ownerUserId;
    @Column(nullable = false)
    private String managedBy = "manual";
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getClawId() { return clawId; }
    public void setClawId(String clawId) { this.clawId = clawId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getPeerKind() { return peerKind; }
    public void setPeerKind(String peerKind) { this.peerKind = peerKind; }
    public String getPeerId() { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }
    public String getParentPeerKind() { return parentPeerKind; }
    public void setParentPeerKind(String parentPeerKind) { this.parentPeerKind = parentPeerKind; }
    public String getParentPeerId() { return parentPeerId; }
    public void setParentPeerId(String parentPeerId) { this.parentPeerId = parentPeerId; }
    public String getGuildId() { return guildId; }
    public void setGuildId(String guildId) { this.guildId = guildId; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public String getRolesJson() { return rolesJson; }
    public void setRolesJson(String rolesJson) { this.rolesJson = rolesJson; }
    public String getSenderIdsJson() { return senderIdsJson; }
    public void setSenderIdsJson(String senderIdsJson) { this.senderIdsJson = senderIdsJson; }
    public String getMentionAliasesJson() { return mentionAliasesJson; }
    public void setMentionAliasesJson(String mentionAliasesJson) { this.mentionAliasesJson = mentionAliasesJson; }
    public String getCommandPrefixesJson() { return commandPrefixesJson; }
    public void setCommandPrefixesJson(String commandPrefixesJson) { this.commandPrefixesJson = commandPrefixesJson; }
    public String getDmScope() { return dmScope; }
    public void setDmScope(String dmScope) { this.dmScope = dmScope; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getManagedBy() { return managedBy; }
    public void setManagedBy(String managedBy) { this.managedBy = managedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
