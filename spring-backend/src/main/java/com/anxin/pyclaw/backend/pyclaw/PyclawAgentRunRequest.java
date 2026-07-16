package com.anxin.pyclaw.backend.pyclaw;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PyclawAgentRunRequest(
        String prompt,
        String provider,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("tool_profile") String toolProfile,
        String model,
        @JsonProperty("api_mode") String apiMode,
        @JsonProperty("base_url") String baseUrl,
        @JsonProperty("api_key") String apiKey,
        String system,
        @JsonProperty("tools_allow") List<String> toolsAllow,
        @JsonProperty("tools_deny") List<String> toolsDeny,
        @JsonProperty("tools_also_allow") List<String> toolsAlsoAllow,
        @JsonProperty("shell_approval") String shellApproval,
        @JsonProperty("claw_id") String clawId,
        @JsonProperty("owner_user_id") String ownerUserId,
        @JsonProperty("claw_name") String clawName,
        @JsonProperty("role_key") String roleKey,
        @JsonProperty("agent_key") String agentKey,
        @JsonProperty("sandbox_base_url") String sandboxBaseUrl
) {
    public PyclawAgentRunRequest {
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
        if (!"auto".equals(shellApproval) && !"require".equals(shellApproval) && !"deny".equals(shellApproval)) {
            shellApproval = "deny";
        }
    }

    public PyclawAgentRunRequest(
            String prompt, String provider, String sessionId, String toolProfile,
            String model, String apiMode, String baseUrl, String apiKey
    ) {
        this(prompt, provider, sessionId, toolProfile, model, apiMode, baseUrl, apiKey,
                null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
