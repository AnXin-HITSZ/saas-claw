package com.anxin.pyclaw.backend.approval;

import com.anxin.pyclaw.backend.audit.AuditLogService;
import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import com.anxin.pyclaw.backend.claw.ClawEntity;
import com.anxin.pyclaw.backend.claw.ClawRepository;
import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.pyclaw.PyclawApprovalResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class ToolApprovalService {
    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {};

    private final ToolApprovalRequestRepository repository;
    private final ClawRepository claws;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public ToolApprovalService(
            ToolApprovalRequestRepository repository,
            ClawRepository claws,
            ObjectMapper objectMapper,
            AuditLogService auditLogService
    ) {
        this.repository = repository;
        this.claws = claws;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * Persist a MySQL approval row that corresponds to the pending state Python just
     * wrote to Redis. Called from {@code ClawChatService} whenever pyclaw returns
     * {@code PENDING_APPROVAL}.
     */
    @Transactional
    public ToolApprovalResponse createFromPyclaw(
            ClawEntity claw,
            String sessionId,
            String agentId,
            String agentKey,
            String roleKey,
            PyclawApprovalResponse payload,
            Authentication authentication
    ) {
        return createFromPyclaw(claw, sessionId, agentId, agentKey, roleKey, payload,
                null, null, null, null, null, authentication);
    }

    @Transactional
    public ToolApprovalResponse createFromPyclaw(
            ClawEntity claw,
            String sessionId,
            String agentId,
            String agentKey,
            String roleKey,
            PyclawApprovalResponse payload,
            String executingAgentInstanceId,
            String executingRoleKey,
            String callingAgentInstanceId,
            String callingRoleKey,
            String conversationId,
            Authentication authentication
    ) {
        if (payload == null || payload.id() == null || payload.id().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "pyclaw returned PENDING_APPROVAL without an approval payload");
        }
        OffsetDateTime now = OffsetDateTime.now();
        ToolApprovalRequestEntity entity = new ToolApprovalRequestEntity();
        entity.setId(payload.id());
        entity.setOwnerUserId(claw.getOwnerUserId());
        entity.setClawId(claw.getId());
        entity.setClawName(claw.getName());
        entity.setSessionId(sessionId);
        entity.setAgentId(agentId);
        entity.setAgentKey(agentKey);
        entity.setRoleKey(roleKey);
        entity.setToolCallId(safe(payload.toolName(), payload.id()));
        entity.setToolName(payload.toolName());
        entity.setRisk(payload.risk());
        entity.setStatus(ToolApprovalStatus.PENDING);
        entity.setIntentSummary(truncate(payload.intent(), 1024));
        entity.setArgumentsPreview(serializePreview(payload.argumentsPreview()));
        entity.setPendingStateKey(payload.pendingStateKey());
        entity.setExpiresAt(parseExpiresAt(payload.expiresAt(), now.plusMinutes(30)));
        entity.setExecutingAgentInstanceId(executingAgentInstanceId);
        entity.setExecutingRoleKey(executingRoleKey);
        entity.setCallingAgentInstanceId(callingAgentInstanceId);
        entity.setCallingRoleKey(callingRoleKey);
        entity.setConversationId(conversationId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        ToolApprovalRequestEntity saved = repository.save(entity);
        writeAudit(authentication, "tool.approval.created", saved, true, null);
        return toResponse(saved);
    }

    public ToolApprovalRequestEntity requireOwnedPending(
            String clawId,
            String approvalId,
            AuthenticatedPrincipal principal
    ) {
        return requireOwnedActionable(clawId, approvalId, principal, null);
    }

    public ToolApprovalRequestEntity requireOwnedActionable(
            String clawId,
            String approvalId,
            AuthenticatedPrincipal principal,
            ToolApprovalDecision decision
    ) {
        if (approvalId == null || approvalId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Approval id is required");
        }
        ToolApprovalRequestEntity entity = repository
                .findByIdAndClawIdAndOwnerUserId(approvalId, clawId, principal.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Approval not found"));
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(OffsetDateTime.now())) {
            entity.setStatus(ToolApprovalStatus.EXPIRED);
            entity.setUpdatedAt(OffsetDateTime.now());
            repository.save(entity);
            writeAudit(null, "tool.approval.expired", entity, true, null);
            throw new ApiException(HttpStatus.GONE, "Approval has expired");
        }
        if (entity.getStatus() == ToolApprovalStatus.PENDING) {
            return entity;
        }
        if (entity.getStatus() == ToolApprovalStatus.RESUME_FAILED
                && decision != null
                && entity.getDecision() == decision) {
            return entity;
        }
        throw new ApiException(HttpStatus.CONFLICT, "Approval is not actionable (current status: " + entity.getStatus() + ")");
    }

    @Transactional
    public ToolApprovalRequestEntity markApprovedForResume(
            ToolApprovalRequestEntity entity,
            Authentication authentication,
            AuthenticatedPrincipal principal
    ) {
        entity.setStatus(ToolApprovalStatus.RESUMING);
        entity.setDecision(ToolApprovalDecision.APPROVED);
        entity.setResolvedBy(principal.userId());
        entity.setResolvedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        ToolApprovalRequestEntity saved = repository.save(entity);
        writeAudit(authentication, "tool.approval.approved", saved, true, null);
        return saved;
    }

    @Transactional
    public ToolApprovalRequestEntity markRejectedForResume(
            ToolApprovalRequestEntity entity,
            String reason,
            Authentication authentication,
            AuthenticatedPrincipal principal
    ) {
        entity.setStatus(ToolApprovalStatus.RESUMING);
        entity.setDecision(ToolApprovalDecision.REJECTED);
        entity.setResolvedBy(principal.userId());
        entity.setResolvedAt(OffsetDateTime.now());
        entity.setRejectReason(truncate(reason, 1024));
        entity.setUpdatedAt(OffsetDateTime.now());
        ToolApprovalRequestEntity saved = repository.save(entity);
        writeAudit(authentication, "tool.approval.rejected", saved, true, reason);
        return saved;
    }

    @Transactional
    public ToolApprovalRequestEntity markConsumed(
            ToolApprovalRequestEntity entity,
            Authentication authentication
    ) {
        entity.setStatus(ToolApprovalStatus.CONSUMED);
        entity.setUpdatedAt(OffsetDateTime.now());
        ToolApprovalRequestEntity saved = repository.save(entity);
        writeAudit(authentication, "tool.approval.consumed", saved, true, null);
        return saved;
    }

    @Transactional
    public ToolApprovalRequestEntity markResumeFailed(
            ToolApprovalRequestEntity entity,
            Authentication authentication,
            String error
    ) {
        entity.setStatus(ToolApprovalStatus.RESUME_FAILED);
        entity.setUpdatedAt(OffsetDateTime.now());
        ToolApprovalRequestEntity saved = repository.save(entity);
        writeAudit(authentication, "tool.approval.resume_failed", saved, false, error);
        return saved;
    }

    public ClawEntity requireOwnedClaw(String clawId, AuthenticatedPrincipal principal) {
        ClawEntity claw = claws.findById(clawId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claw not found"));
        if (!Objects.equals(claw.getOwnerUserId(), principal.userId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Claw not found");
        }
        return claw;
    }

    public ToolApprovalResponse toResponse(ToolApprovalRequestEntity entity) {
        Map<String, Object> preview = deserializePreview(entity.getArgumentsPreview());
        return new ToolApprovalResponse(
                entity.getId(),
                entity.getStatus() == null ? ToolApprovalStatus.PENDING.name() : entity.getStatus().name(),
                entity.getDecision() == null ? null : entity.getDecision().name(),
                entity.getClawId(),
                entity.getClawName(),
                entity.getSessionId(),
                entity.getAgentId(),
                entity.getAgentKey(),
                entity.getRoleKey(),
                entity.getToolName(),
                entity.getRisk(),
                entity.getIntentSummary(),
                preview,
                entity.getPendingStateKey(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.getResolvedAt(),
                entity.getRejectReason(),
                entity.getExecutingAgentInstanceId(),
                entity.getExecutingRoleKey(),
                entity.getCallingAgentInstanceId(),
                entity.getCallingRoleKey(),
                entity.getConversationId()
        );
    }

    private String serializePreview(Map<String, Object> preview) {
        if (preview == null || preview.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(preview);
        } catch (JsonProcessingException exc) {
            return "{}";
        }
    }

    private Map<String, Object> deserializePreview(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_OBJECT_MAP);
        } catch (Exception exc) {
            return Map.of();
        }
    }

    private OffsetDateTime parseExpiresAt(String value, OffsetDateTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException exc) {
            return fallback;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String safe(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private void writeAudit(Authentication authentication, String action, ToolApprovalRequestEntity entity, boolean success, String error) {
        String actorType = "USER";
        String actorId = entity.getOwnerUserId();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            actorType = principal.actorType();
            actorId = principal.userId();
        }
        auditLogService.record(actorType, actorId, action, "tool_approval", entity.getId(), success, error);
    }
}
