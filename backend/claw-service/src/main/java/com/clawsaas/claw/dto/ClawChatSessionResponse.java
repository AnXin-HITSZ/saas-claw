package com.clawsaas.claw.dto;

import java.time.OffsetDateTime;

public record ClawChatSessionResponse(
        String sessionId,
        String clawId,
        String clawName,
        String roleKey,
        String agentId,
        String agentKey,
        String provider,
        String model,
        int messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime lastActiveAt
) {
}
