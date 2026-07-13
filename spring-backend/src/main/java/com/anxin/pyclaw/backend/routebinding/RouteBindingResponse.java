package com.anxin.pyclaw.backend.routebinding;

import java.time.OffsetDateTime;
import java.util.List;

public record RouteBindingResponse(
        String id,
        boolean enabled,
        int priority,
        String clawId,
        String agentId,
        String agentKey,
        String agentName,
        String channel,
        String accountId,
        String peerKind,
        String peerId,
        String parentPeerKind,
        String parentPeerId,
        String guildId,
        String teamId,
        List<String> roles,
        List<String> senderIds,
        List<String> mentionAliases,
        List<String> commandPrefixes,
        String dmScope,
        String comment,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
