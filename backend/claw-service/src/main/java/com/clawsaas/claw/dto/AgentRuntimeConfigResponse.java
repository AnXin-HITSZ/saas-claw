package com.clawsaas.claw.dto;

import java.time.OffsetDateTime;

public record AgentRuntimeConfigResponse(
        String agentId,
        String agentKey,
        String name,
        boolean enabled,
        String provider,
        String model,
        String apiMode,
        String baseUrl,
        String apiKey,
        String system,
        String workspaceDir,
        String runtimeType,
        AgentRuntimeToolPolicyResponse toolPolicy,
        String configVersion,
        OffsetDateTime updatedAt
) {
}
