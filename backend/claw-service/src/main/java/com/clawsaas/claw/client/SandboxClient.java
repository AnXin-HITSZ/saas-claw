package com.clawsaas.claw.client;

/**
 * Stub for sandbox lifecycle operations. Future: HTTP client to runtime-service.
 */
public interface SandboxClient {
    void ensureClawSandbox(String ownerUserId, String actorName, String clawId, String clawName);
    void deleteClawSandbox(String ownerUserId, String clawId);
    void scaleClawDeployment(String ownerUserId, String clawId, int replicas);
    boolean isEnabled();
    void healthz(String ownerUserId, String clawId);
}
