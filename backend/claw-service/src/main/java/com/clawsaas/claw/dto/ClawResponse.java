package com.clawsaas.claw.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record ClawResponse(
        String id,
        String ownerUserId,
        String name,
        String description,
        String status,
        String defaultAgentId,
        boolean feishuEnabled,
        String feishuAccountId,
        String feishuPeerKind,
        String feishuPeerId,
        String feishuComment,
        List<ClawRoleResponse> roles,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
