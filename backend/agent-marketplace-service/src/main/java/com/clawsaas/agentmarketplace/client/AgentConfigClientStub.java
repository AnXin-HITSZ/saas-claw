package com.clawsaas.agentmarketplace.client;

import org.springframework.stereotype.Service;

/**
 * Stub implementation of {@link AgentConfigClient}.
 *
 * <p>Phase 1 placeholder — will be replaced with a real HTTP client
 * calling the agent-config-service in a later phase.
 */
@Service
public class AgentConfigClientStub implements AgentConfigClient {

    @Override
    public AgentConfigDTO findById(String agentId) {
        throw new UnsupportedOperationException(
                "AgentConfigClient not yet implemented — will call agent-config-service");
    }
}
