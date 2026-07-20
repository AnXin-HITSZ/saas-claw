package com.clawsaas.gateway.dto;

import java.util.List;

public record MeResponse(
        String userId,
        String username,
        String actorType,
        List<String> authorities
) {
}
