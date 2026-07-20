package com.clawsaas.claw.dto;

import jakarta.validation.constraints.NotBlank;

public record ClawChatRunRequest(
        @NotBlank String prompt,
        String roleKey,
        String sessionId,
        // Task 4 additions (ARCHITECTURE.md)
        String conversationId,
        String agentInstanceId
) {
}
