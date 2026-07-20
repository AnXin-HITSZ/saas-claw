package com.clawsaas.gateway.service.impl;

import com.clawsaas.gateway.service.SandboxOrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * No-op stub for sandbox orchestration in gateway.
 * Gateway does not manage Kubernetes resources — this is delegated to runtime-service.
 */
@Service
public class SandboxOrchestratorServiceImpl implements SandboxOrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(SandboxOrchestratorServiceImpl.class);

    @Override
    public void ensureUserNamespace(String userId, String username) {
        log.info("Sandbox orchestration is disabled in gateway; namespace creation for user {} delegated to runtime-service", userId);
    }

    @Override
    public void scaleUserDeployments(String userId, int replicas) {
        log.info("Sandbox orchestration is disabled in gateway; scaling for user {} delegated to runtime-service", userId);
    }
}
