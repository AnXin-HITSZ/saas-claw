package com.anxin.pyclaw.backend.claw;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ClawRequest(
        @NotBlank String name,
        String description,
        String status,
        String defaultAgentId,
        Boolean feishuEnabled,
        String feishuAccountId,
        String feishuPeerKind,
        String feishuPeerId,
        String feishuComment,
        @Valid List<ClawRoleRequest> roles
) {
}
