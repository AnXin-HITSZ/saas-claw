package com.clawsaas.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.security")
public record SecurityProperties(
        String jwtSecret,
        long jwtTtlSeconds,
        String bootstrapAdminUsername,
        String bootstrapAdminPassword,
        String encryptionSecret
) {
}
