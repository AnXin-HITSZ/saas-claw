package com.anxin.pyclaw.backend.claw;

import java.time.OffsetDateTime;
import java.util.List;

public record ClawRoleResponse(
        String id,
        String clawId,
        String agentId,
        String agentKey,
        String agentName,
        String roleKey,
        String displayName,
        List<String> mentionAliases,
        List<String> commandPrefixes,
        boolean defaultRole,
        boolean enabled,
        int sortOrder,
        String routeBindingId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
