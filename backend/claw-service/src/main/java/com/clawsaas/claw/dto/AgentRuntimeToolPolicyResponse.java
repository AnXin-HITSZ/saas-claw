package com.clawsaas.claw.dto;

import java.util.List;

public record AgentRuntimeToolPolicyResponse(
        String profile,
        List<String> allow,
        List<String> deny,
        List<String> alsoAllow,
        boolean readonly
) {
}
