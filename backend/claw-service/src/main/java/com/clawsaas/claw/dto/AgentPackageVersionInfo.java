package com.clawsaas.claw.dto;

import java.time.OffsetDateTime;

/**
 * Stub DTO representing agent package version.
 */
public record AgentPackageVersionInfo(
        String id,
        String packageId,
        String version,
        String status,
        String defaultProfile,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
