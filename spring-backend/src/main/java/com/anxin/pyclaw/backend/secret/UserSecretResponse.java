package com.anxin.pyclaw.backend.secret;

import java.time.OffsetDateTime;
import java.util.Map;

public record UserSecretResponse(
        String id,
        String name,
        String type,
        String scope,
        String clawId,
        String kubernetesSecretName,
        Map<String, String> maskedValues,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
