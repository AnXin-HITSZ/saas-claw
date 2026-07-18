package com.anxin.pyclaw.backend.orchestrator;

import jakarta.validation.constraints.NotBlank;

public record OrchestratorCallRequest(
        @NotBlank String clawId,
        @NotBlank String callingAgentInstanceId,
        String targetAgentInstanceId,
        String targetRoleKey,
        @NotBlank String message,
        String conversationId
) {}
