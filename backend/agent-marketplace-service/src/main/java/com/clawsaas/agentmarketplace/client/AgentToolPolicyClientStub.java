package com.clawsaas.agentmarketplace.client;

import org.springframework.stereotype.Service;

/**
 * Stub implementation of {@link AgentToolPolicyClient}.
 *
 * <p>Phase 1 placeholder — will be replaced with a real HTTP client
 * calling the agent-config-service in a later phase.
 */
@Service
public class AgentToolPolicyClientStub implements AgentToolPolicyClient {

    @Override
    public AgentToolPolicyDTO findByAgentId(String agentId) {
        throw new UnsupportedOperationException(
                "AgentToolPolicyClient not yet implemented — will call agent-config-service");
    }
}
