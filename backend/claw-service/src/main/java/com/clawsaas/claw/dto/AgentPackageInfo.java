package com.clawsaas.claw.dto;

import java.time.OffsetDateTime;

/**
 * Stub DTO representing agent package metadata.
 */
public record AgentPackageInfo(
        String id,
        String packageKey,
        String name,
        String visibility,
        String ownerUserId,
        int installCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
