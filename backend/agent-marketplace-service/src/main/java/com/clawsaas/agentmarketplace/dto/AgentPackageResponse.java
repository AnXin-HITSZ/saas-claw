package com.clawsaas.agentmarketplace.dto;

import java.time.OffsetDateTime;

public record AgentPackageResponse(
        String id,
        String packageKey,
        String ownerUserId,
        String name,
        String summary,
        String description,
        String visibility,
        String latestVersionId,
        long installCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
