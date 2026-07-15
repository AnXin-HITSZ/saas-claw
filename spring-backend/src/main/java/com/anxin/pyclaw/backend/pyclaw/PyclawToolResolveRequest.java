package com.anxin.pyclaw.backend.pyclaw;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PyclawToolResolveRequest(
        String profile,
        List<String> allow,
        List<String> deny,
        @JsonProperty("also_allow") List<String> alsoAllow,
        Boolean readonly,
        @JsonProperty("workspace_mode") String workspaceMode,
        @JsonProperty("web_access") Boolean webAccess
) {
}
