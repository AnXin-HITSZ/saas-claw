package com.clawsaas.claw.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record AgentToolPolicyResponse(
        String id,
        String profile,
        List<String> toolsAllow,
        List<String> toolsDeny,
        List<String> toolsAlsoAllow,
        boolean readonly,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
