package com.clawsaas.gateway.service;

import com.clawsaas.gateway.entity.AgentToolPolicyEntity;

/**
 * Stub interface for AgentConfig domain logic.
 * Full implementation lives in agent-marketplace-service.
 */
public interface AgentConfigService {
    AgentToolPolicyEntity requirePolicy(String agentId);
}
