package com.anxin.pyclaw.backend.clawchat;

import com.anxin.pyclaw.backend.approval.ToolApprovalResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

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
        ToolApprovalResponse approval
) {
    public ClawChatRunResponse(String sessionId, String clawId, String roleKey, String agentId, String agentKey,
                               String text, Map<String, Object> message, long latencyMs) {
        this("COMPLETED", sessionId, clawId, roleKey, agentId, agentKey, text, message, latencyMs, null);
    }
}
