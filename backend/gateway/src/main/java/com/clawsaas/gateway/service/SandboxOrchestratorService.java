package com.clawsaas.gateway.service;

/**
 * Stub for sandbox orchestration.
 * Full implementation lives in runtime-service.
 * Gateway should NOT directly manage Kubernetes resources.
 */
public interface SandboxOrchestratorService {
    void ensureUserNamespace(String userId, String username);

    void scaleUserDeployments(String userId, int replicas);
}
