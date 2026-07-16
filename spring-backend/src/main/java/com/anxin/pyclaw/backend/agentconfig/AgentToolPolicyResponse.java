package com.anxin.pyclaw.backend.agentconfig;

import java.time.OffsetDateTime;
import java.util.List;

public record AgentToolPolicyResponse(
        String id,
        String profile,
        List<String> toolsAllow,
        List<String> toolsDeny,
        List<String> toolsAlsoAllow,
        boolean readonly,
        String shellApproval,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
