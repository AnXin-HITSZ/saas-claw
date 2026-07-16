package com.anxin.pyclaw.backend.pyclaw;

import java.util.List;

public record PyclawToolResolveRequest(
        String profile,
        List<String> allow,
        List<String> deny,
        List<String> alsoAllow,
        Boolean readonly
) {
}
