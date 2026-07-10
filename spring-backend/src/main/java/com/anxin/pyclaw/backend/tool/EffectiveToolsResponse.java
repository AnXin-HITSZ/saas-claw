package com.anxin.pyclaw.backend.tool;

import java.util.List;

public record EffectiveToolsResponse(
        String profile,
        List<String> effectiveTools,
        List<String> deniedTools
) {
}
