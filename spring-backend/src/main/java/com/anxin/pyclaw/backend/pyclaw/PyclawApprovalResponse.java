package com.anxin.pyclaw.backend.pyclaw;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record PyclawApprovalResponse(
        String id,
        @JsonProperty("tool_name") String toolName,
        String risk,
        String intent,
        @JsonProperty("arguments_preview") Map<String, Object> argumentsPreview,
        @JsonProperty("pending_state_key") String pendingStateKey,
        @JsonProperty("expires_at") String expiresAt
) {
}
