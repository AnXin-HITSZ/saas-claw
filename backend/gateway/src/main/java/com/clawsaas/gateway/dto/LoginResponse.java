package com.clawsaas.gateway.dto;

public record LoginResponse(
        String accessToken,
        long expiresIn
) {
}
