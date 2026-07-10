package com.anxin.pyclaw.backend.provider;

public record ProviderOptionResponse(
        String id,
        String name,
        String providerType,
        String model,
        String apiMode,
        boolean enabled
) {
    public static ProviderOptionResponse from(ProviderConfigEntity entity) {
        return new ProviderOptionResponse(
                entity.getId(),
                entity.getName(),
                entity.getProviderType(),
                entity.getModel(),
                entity.getApiMode(),
                entity.isEnabled()
        );
    }
}
