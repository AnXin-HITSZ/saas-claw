package com.anxin.pyclaw.backend.tool;

import java.util.List;

public record ToolCatalogEntryResponse(
        String name,
        String label,
        String description,
        String sectionId,
        List<String> profiles,
        List<String> tags,
        String risk,
        boolean workspaceOnly
) {
}
