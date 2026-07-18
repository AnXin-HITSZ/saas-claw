package com.anxin.pyclaw.backend.agentinstall;

public record AgentInstancePatchRequest(
        String roleKey,
        String displayName,
        String localProfile,
        String localSystemPromptOverride,
        Boolean enabled,
        Boolean defaultRole,
        Integer sortOrder
) {}
