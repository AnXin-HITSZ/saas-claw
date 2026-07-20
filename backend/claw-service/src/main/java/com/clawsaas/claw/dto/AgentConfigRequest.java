package com.clawsaas.claw.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public record AgentConfigRequest(
        @NotBlank String agentKey,
        @NotBlank String name,
        String description,
        Boolean enabled,
        String providerId,
        String provider,
        String model,
        String systemPrompt,
        String workspaceDir,
        String runtimeType,
        @Valid AgentToolPolicyRequest toolPolicy
) {
}
