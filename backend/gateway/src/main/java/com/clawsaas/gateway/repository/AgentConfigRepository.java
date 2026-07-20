package com.clawsaas.gateway.repository;

import com.clawsaas.gateway.entity.AgentConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Stub repository for AgentConfig.
 * Full repository lives in agent-marketplace-service.
 */
public interface AgentConfigRepository extends JpaRepository<AgentConfigEntity, String> {
}
