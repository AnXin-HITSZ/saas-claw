package com.clawsaas.claw.dto;

import java.time.OffsetDateTime;

public record AgentConfigResponse(
        String id,
        String agentKey,
        String name,
        String description,
        boolean enabled,
        String providerId,
        String provider,
        String model,
        String systemPrompt,
        String workspaceDir,
        String runtimeType,
        String createdBy,
        AgentToolPolicyResponse toolPolicy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
