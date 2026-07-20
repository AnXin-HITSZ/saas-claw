package com.clawsaas.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stub audit log service for gateway.
 * Full implementation will live in a shared audit service or be handled
 * by individual domain services.
 */
@Service
public class AuditLogService {
    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    public void record(String actorType, String actorId, String action, String resourceType,
                        String resourceId, boolean success, String errorMessage) {
        log.debug("Audit: actorType={} actorId={} action={} resourceType={} resourceId={} success={}",
                actorType, actorId, action, resourceType, resourceId, success);
    }
}
