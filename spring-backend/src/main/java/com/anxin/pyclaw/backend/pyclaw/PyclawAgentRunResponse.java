package com.anxin.pyclaw.backend.pyclaw;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record PyclawAgentRunResponse(
        String status,
        @JsonProperty("session_id") String sessionId,
        Map<String, Object> message,
        String text,
        PyclawApprovalResponse approval
) {
    public PyclawAgentRunResponse {
        if (status == null || status.isBlank()) {
            status = "COMPLETED";
        }
        if (text == null) {
            text = "";
        }
    }

    public PyclawAgentRunResponse(String sessionId, Map<String, Object> message, String text) {
        this("COMPLETED", sessionId, message, text, null);
    }
}
