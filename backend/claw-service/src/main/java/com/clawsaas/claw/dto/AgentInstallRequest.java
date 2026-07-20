package com.clawsaas.claw.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentInstallRequest(
        @NotBlank String packageVersionId,
        String roleKey,
        String displayName,
        String localProfile
) {}
