package com.anxin.pyclaw.backend.agentconfig;

import java.util.List;

public record AgentRuntimeToolPolicyResponse(
        String profile,
        List<String> allow,
        List<String> deny,
        List<String> alsoAllow,
        boolean readonly,
        String shellApproval
) {
}
