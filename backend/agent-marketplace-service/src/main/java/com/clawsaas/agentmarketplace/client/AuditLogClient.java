package com.clawsaas.agentmarketplace.client;

public interface AuditLogClient {

    void record(String actorType, String actorId, String action, String resourceType,
                String resourceId, boolean success, String errorMessage);
}
