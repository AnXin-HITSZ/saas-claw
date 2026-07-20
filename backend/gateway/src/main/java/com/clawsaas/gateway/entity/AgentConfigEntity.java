package com.clawsaas.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Minimal stub entity for AgentConfig.
 * Full entity lives in agent-marketplace-service.
 */
@Entity
@Table(name = "agents")
public class AgentConfigEntity {
    @Id
    private String id;
    @Column(nullable = false, unique = true)
    private String agentKey;
    @Column(nullable = false)
    private String name;
    private String createdBy;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentKey() { return agentKey; }
    public void setAgentKey(String agentKey) { this.agentKey = agentKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
