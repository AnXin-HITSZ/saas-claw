package com.clawsaas.gateway.service;

import com.clawsaas.gateway.entity.AgentToolPolicyEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Stub validator for tool policy grants.
 * Full implementation lives in agent-marketplace-service.
 */
@Service
public class ToolPolicyGrantValidator {

    public void requireCanRouteTo(AgentToolPolicyEntity policy, Authentication authentication) {
        // No-op: full validation lives in agent-marketplace-service
    }
}
