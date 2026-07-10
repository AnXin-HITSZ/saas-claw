package com.anxin.pyclaw.backend.tool;

import java.util.List;

public record EffectiveToolsRequest(
        String profile,
        List<String> allow,
        List<String> deny,
        List<String> alsoAllow,
        Boolean readonly
) {
}
