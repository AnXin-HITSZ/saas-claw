package com.clawsaas.claw.dto;

public record SessionMessageResponse(
        String role,
        String content,
        long timestamp
) {}
