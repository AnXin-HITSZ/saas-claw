package com.anxin.pyclaw.backend.pyclaw;

import java.util.List;

public record PyclawToolCatalogResponse(
        List<String> profiles,
        List<PyclawToolCatalogEntry> tools
) {
}
