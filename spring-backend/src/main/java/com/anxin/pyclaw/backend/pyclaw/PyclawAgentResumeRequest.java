package com.anxin.pyclaw.backend.pyclaw;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PyclawAgentResumeRequest(
        @JsonProperty("approval_id") String approvalId,
        String decision,
        @JsonProperty("rejection_reason") String rejectionReason,
        String provider,
        String model,
        @JsonProperty("api_mode") String apiMode,
        @JsonProperty("base_url") String baseUrl,
        @JsonProperty("api_key") String apiKey,
        String system,
        @JsonProperty("tool_profile") String toolProfile,
        @JsonProperty("tools_allow") List<String> toolsAllow,
        @JsonProperty("tools_deny") List<String> toolsDeny,
        @JsonProperty("tools_also_allow") List<String> toolsAlsoAllow,
        @JsonProperty("sandbox_base_url") String sandboxBaseUrl
) {
    public PyclawAgentResumeRequest {
        if (toolProfile == null || toolProfile.isBlank()) {
            toolProfile = "minimal";
        }
        if (apiMode == null || apiMode.isBlank()) {
            apiMode = "auto";
        }
        if (system == null) {
            system = "";
        }
        if (toolsAllow == null) {
            toolsAllow = List.of();
        }
        if (toolsDeny == null) {
            toolsDeny = List.of();
        }
        if (toolsAlsoAllow == null) {
            toolsAlsoAllow = List.of();
        }
    }
}
