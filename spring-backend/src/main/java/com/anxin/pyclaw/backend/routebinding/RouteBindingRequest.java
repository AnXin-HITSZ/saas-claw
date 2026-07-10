package com.anxin.pyclaw.backend.routebinding;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record RouteBindingRequest(
        Boolean enabled,
        Integer priority,
        @NotBlank String agentId,
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
        String comment
) {
}
