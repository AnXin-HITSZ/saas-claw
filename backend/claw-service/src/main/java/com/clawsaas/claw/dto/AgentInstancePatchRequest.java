package com.clawsaas.claw.dto;

public record AgentInstancePatchRequest(
        String roleKey,
        String displayName,
        String localProfile,
        String localSystemPromptOverride,
        Boolean enabled,
        Boolean defaultRole,
        Integer sortOrder
) {}
