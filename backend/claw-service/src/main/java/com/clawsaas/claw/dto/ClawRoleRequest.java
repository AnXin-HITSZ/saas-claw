package com.clawsaas.claw.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ClawRoleRequest(
        String id,
        @NotBlank String agentId,
        @NotBlank String roleKey,
        @NotBlank String displayName,
        List<String> mentionAliases,
        List<String> commandPrefixes,
        Boolean defaultRole,
        Boolean enabled,
        Integer sortOrder
) {
}
