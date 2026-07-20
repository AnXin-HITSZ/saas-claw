package com.clawsaas.agentmarketplace.dto;

import java.time.OffsetDateTime;

public record AgentPackageVersionResponse(
        String id,
        String packageId,
        String version,
        String status,
        String defaultProfile,
        String requiredProfile,
        String changelog,
        String manifestJson,
        OffsetDateTime createdAt
) {}
