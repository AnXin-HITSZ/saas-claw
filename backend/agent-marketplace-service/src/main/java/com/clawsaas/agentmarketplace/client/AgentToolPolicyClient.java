package com.clawsaas.agentmarketplace.client;

public interface AgentToolPolicyClient {

    AgentToolPolicyDTO findByAgentId(String agentId);

    record AgentToolPolicyDTO(
            String id,
            String agentId,
            String profile,
            boolean readonly
    ) {}
}
