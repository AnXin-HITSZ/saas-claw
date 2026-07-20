package com.clawsaas.gateway.service.impl;

import com.clawsaas.gateway.entity.AgentToolPolicyEntity;
import com.clawsaas.gateway.service.AgentConfigService;
import org.springframework.stereotype.Service;

/**
 * Stub implementation for AgentConfig domain logic.
 * Full implementation lives in agent-marketplace-service.
 */
@Service
public class AgentConfigServiceImpl implements AgentConfigService {

    @Override
    public AgentToolPolicyEntity requirePolicy(String agentId) {
        AgentToolPolicyEntity policy = new AgentToolPolicyEntity();
        policy.setAgentId(agentId);
        policy.setProfile("messaging");
        return policy;
    }
}
