package com.clawsaas.claw.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub for audit logging. In the new architecture, audit is a cross-cutting
 * concern. For now, log via SLF4J. Future: HTTP client to a dedicated audit
 * service or a local AuditLogEntity + AuditLogRepository in claw-service.
 */
public interface AuditLogClient {

    Logger AUDIT_LOG = LoggerFactory.getLogger("com.clawsaas.claw.audit");

    default void record(String actorType, String actorId, String action,
                        String resourceType, String resourceId, boolean success, String errorMessage) {
        AUDIT_LOG.info("AUDIT actorType={} actorId={} action={} resourceType={} resourceId={} success={} error={}",
                actorType, actorId, action, resourceType, resourceId, success, errorMessage);
    }
}
