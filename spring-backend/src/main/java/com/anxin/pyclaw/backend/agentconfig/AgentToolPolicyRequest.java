package com.anxin.pyclaw.backend.agentconfig;

import java.util.List;

public record AgentToolPolicyRequest(
        String profile,
        List<String> toolsAllow,
        List<String> toolsDeny,
        List<String> toolsAlsoAllow,
        Boolean readonly,
        String shellApproval
) {
}
