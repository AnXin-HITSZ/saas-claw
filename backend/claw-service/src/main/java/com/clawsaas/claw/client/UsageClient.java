package com.clawsaas.claw.client;

/**
 * Stub for billing-service usage tracking.
 */
public interface UsageClient {
    void recordUsage(String userId, String clawId, String agentId, String agentKey,
                     String roleKey, String sessionId, String provider, String model,
                     Object response, long latencyMs);
}
