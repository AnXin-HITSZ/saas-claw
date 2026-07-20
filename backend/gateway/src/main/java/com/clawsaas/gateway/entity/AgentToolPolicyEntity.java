package com.clawsaas.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Minimal stub entity for AgentToolPolicy.
 * Full entity lives in agent-marketplace-service.
 */
@Entity
@Table(name = "agent_tool_policies")
public class AgentToolPolicyEntity {
    @Id
    private String id;
    private String agentId;
    private String profile;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
}
