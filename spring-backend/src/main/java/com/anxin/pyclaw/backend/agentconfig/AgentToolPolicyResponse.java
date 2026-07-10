package com.anxin.pyclaw.backend.agentconfig;

import java.time.OffsetDateTime;
import java.util.List;

public record AgentToolPolicyResponse(
        String id,
        String profile,
        List<String> toolsAllow,
        List<String> toolsDeny,
        List<String> toolsAlsoAllow,
        boolean workspaceOnly,
        boolean readonly,
        String shellApproval,
        boolean webAccess,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
