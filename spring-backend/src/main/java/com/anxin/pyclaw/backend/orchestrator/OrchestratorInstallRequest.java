package com.anxin.pyclaw.backend.orchestrator;

import jakarta.validation.constraints.NotBlank;

public record OrchestratorInstallRequest(
        @NotBlank String clawId,
        @NotBlank String packageVersionId,
        String requestingAgentInstanceId,
        String reason
) {}
