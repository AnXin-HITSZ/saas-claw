package com.clawsaas.agentmarketplace.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stub implementation of {@link AuditLogClient}.
 *
 * <p>Phase 1 placeholder — logs audit events locally instead of sending
 * them to the audit service. Will be replaced with a real HTTP client
 * in a later phase.
 */
@Service
public class AuditLogClientStub implements AuditLogClient {

    private static final Logger log = LoggerFactory.getLogger(AuditLogClientStub.class);

    @Override
    public void record(String actorType, String actorId, String action, String resourceType,
                       String resourceId, boolean success, String errorMessage) {
        log.info("AUDIT actorType={} actorId={} action={} resourceType={} resourceId={} success={} error={}",
                actorType, actorId, action, resourceType, resourceId, success, errorMessage);
    }
}
