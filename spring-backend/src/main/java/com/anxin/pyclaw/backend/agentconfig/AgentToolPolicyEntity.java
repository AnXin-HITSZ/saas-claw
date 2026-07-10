package com.anxin.pyclaw.backend.agentconfig;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "agent_tool_policies")
public class AgentToolPolicyEntity {
    @Id
    private String id;
    @Column(nullable = false, unique = true)
    private String agentId;
    @Column(nullable = false)
    private String profile;
    @Lob
    private String toolsAllowJson;
    @Lob
    private String toolsDenyJson;
    @Lob
    private String toolsAlsoAllowJson;
    @Column(nullable = false)
    private boolean workspaceOnly;
    @Column(nullable = false)
    private boolean readonly;
    @Column(nullable = false)
    private String shellApproval;
    @Column(nullable = false)
    private boolean webAccess;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
    public String getToolsAllowJson() { return toolsAllowJson; }
    public void setToolsAllowJson(String toolsAllowJson) { this.toolsAllowJson = toolsAllowJson; }
    public String getToolsDenyJson() { return toolsDenyJson; }
    public void setToolsDenyJson(String toolsDenyJson) { this.toolsDenyJson = toolsDenyJson; }
    public String getToolsAlsoAllowJson() { return toolsAlsoAllowJson; }
    public void setToolsAlsoAllowJson(String toolsAlsoAllowJson) { this.toolsAlsoAllowJson = toolsAlsoAllowJson; }
    public boolean isWorkspaceOnly() { return workspaceOnly; }
    public void setWorkspaceOnly(boolean workspaceOnly) { this.workspaceOnly = workspaceOnly; }
    public boolean isReadonly() { return readonly; }
    public void setReadonly(boolean readonly) { this.readonly = readonly; }
    public String getShellApproval() { return shellApproval; }
    public void setShellApproval(String shellApproval) { this.shellApproval = shellApproval; }
    public boolean isWebAccess() { return webAccess; }
    public void setWebAccess(boolean webAccess) { this.webAccess = webAccess; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
