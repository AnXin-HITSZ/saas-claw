package com.anxin.pyclaw.backend.pyclaw;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PyclawToolResolveResponse(
        String profile,
        @JsonProperty("workspace_mode") String workspaceMode,
        List<PyclawToolCatalogEntry> tools,
        @JsonProperty("denied_tools") List<PyclawDeniedTool> deniedTools,
        @JsonProperty("prompt_fragments") List<PyclawPromptFragment> promptFragments
) {
}
