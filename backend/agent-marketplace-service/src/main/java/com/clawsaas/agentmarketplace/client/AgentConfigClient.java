package com.clawsaas.agentmarketplace.client;

public interface AgentConfigClient {

    AgentConfigDTO findById(String agentId);

    record AgentConfigDTO(
            String id,
            String agentKey,
            String name,
            String description,
            String provider,
            String model,
            String systemPrompt,
            String createdBy
    ) {}
}
