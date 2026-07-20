package com.clawsaas.agentmarketplace.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentPublishRequest(
        @NotBlank String packageKey,
        @NotBlank String version,
        String visibility,
        String summary,
        String changelog
) {}
