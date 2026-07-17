package com.anxin.pyclaw.backend.pyclaw;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PyclawToolCatalogEntry(
        String name,
        String label,
        String description,
        @JsonProperty("section_id") String sectionId,
        @JsonProperty("execution_scope") String executionScope,
        List<String> profiles,
        List<String> tags,
        String risk,
        boolean readonly,
        @JsonProperty("prompt_hint") String promptHint
) {
}
