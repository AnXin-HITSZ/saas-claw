package com.clawsaas.claw.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Response DTO for Claw Chat runs. References stubbed ToolApprovalResponse.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClawChatRunResponse(
        String status,
        String sessionId,
        String clawId,
        String roleKey,
        String agentId,
        String agentKey,
        String text,
        Map<String, Object> message,
        long latencyMs,
        ToolApprovalResponse approval,
        String conversationId,
        String agentInstanceId
) {
}
