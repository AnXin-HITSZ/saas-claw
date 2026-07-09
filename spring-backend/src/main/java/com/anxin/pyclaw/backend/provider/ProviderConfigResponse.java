package com.anxin.pyclaw.backend.provider;

import java.time.OffsetDateTime;

public record ProviderConfigResponse(
        String id,
        String name,
        String providerType,
        String baseUrl,
        String model,
        String apiMode,
        String secretRef,
        boolean apiKeyConfigured,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ProviderConfigResponse from(ProviderConfigEntity entity) {
        return new ProviderConfigResponse(
                entity.getId(),
                entity.getName(),
                entity.getProviderType(),
                entity.getBaseUrl(),
                entity.getModel(),
                entity.getApiMode(),
                entity.getSecretRef(),
                entity.getApiKey() != null && !entity.getApiKey().isBlank(),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
