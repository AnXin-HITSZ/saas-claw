package com.anxin.pyclaw.backend.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anxin.pyclaw.backend.audit.AuditLogService;
import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import com.anxin.pyclaw.backend.claw.ClawEntity;
import com.anxin.pyclaw.backend.claw.ClawRepository;
import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.pyclaw.PyclawApprovalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ToolApprovalServiceTest {

    private ToolApprovalRequestRepository repository;
    private ClawRepository claws;
    private AuditLogService auditLogService;
    private ToolApprovalService service;

    @BeforeEach
    void setUp() {
        repository = mock(ToolApprovalRequestRepository.class);
        claws = mock(ClawRepository.class);
        auditLogService = mock(AuditLogService.class);
        service = new ToolApprovalService(repository, claws, new ObjectMapper(), auditLogService);
    }

    private ClawEntity newClaw() {
        ClawEntity claw = new ClawEntity();
        claw.setId("claw-1");
        claw.setOwnerUserId("user-1");
        claw.setName("MyClaw");
        return claw;
    }

    private AuthenticatedPrincipal newPrincipal() {
        return new AuthenticatedPrincipal("user-1", "user-1", "USER", Collections.emptyList());
    }

    @Test
    void createFromPyclawPersistsPendingRow() {
        ClawEntity claw = newClaw();
        PyclawApprovalResponse payload = new PyclawApprovalResponse(
                "approval-1",
                "write_file",
                "medium",
                "写入文件 a.txt",
                Map.of("file_path", "a.txt"),
                "agent:pending_approval:approval-1",
                OffsetDateTime.now().plusMinutes(30).toString()
        );
        when(repository.save(any(ToolApprovalRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

        ToolApprovalResponse response = service.createFromPyclaw(
                claw, "session-1", "agent-1", "agent-key", "role-key", payload, null);

        assertThat(response.id()).isEqualTo("approval-1");
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.decision()).isNull();
        assertThat(response.toolName()).isEqualTo("write_file");
        assertThat(response.risk()).isEqualTo("medium");
        assertThat(response.argumentsPreview()).containsEntry("file_path", "a.txt");
        verify(repository, times(1)).save(any(ToolApprovalRequestEntity.class));
        verify(auditLogService).record(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(true), any());
    }

    @Test
    void requireOwnedPendingRejectsMissingApproval() {
        when(repository.findByIdAndClawIdAndOwnerUserId(any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireOwnedPending("claw-1", "approval-1", newPrincipal()))
                .isInstanceOf(ApiException.class)
                .satisfies(exc -> assertThat(((ApiException) exc).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void requireOwnedPendingRejectsBlankApprovalIdBeforeRepositoryLookup() {
        assertThatThrownBy(() -> service.requireOwnedPending("claw-1", " ", newPrincipal()))
                .isInstanceOf(ApiException.class)
                .satisfies(exc -> assertThat(((ApiException) exc).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void requireOwnedPendingRejectsResolvedApproval() {
        ToolApprovalRequestEntity entity = pendingEntity();
        entity.setStatus(ToolApprovalStatus.CONSUMED);
        entity.setDecision(ToolApprovalDecision.APPROVED);
        when(repository.findByIdAndClawIdAndOwnerUserId(any(), any(), any())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.requireOwnedPending("claw-1", "approval-1", newPrincipal()))
                .isInstanceOf(ApiException.class)
                .satisfies(exc -> assertThat(((ApiException) exc).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void requireOwnedActionableAllowsSameDecisionResumeFailedRetry() {
        ToolApprovalRequestEntity entity = pendingEntity();
        entity.setStatus(ToolApprovalStatus.RESUME_FAILED);
        entity.setDecision(ToolApprovalDecision.APPROVED);
        when(repository.findByIdAndClawIdAndOwnerUserId(any(), any(), any())).thenReturn(Optional.of(entity));

        ToolApprovalRequestEntity result = service.requireOwnedActionable(
                "claw-1", "approval-1", newPrincipal(), ToolApprovalDecision.APPROVED);

        assertThat(result).isSameAs(entity);
    }

    @Test
    void requireOwnedPendingExpiresStaleApproval() {
        ToolApprovalRequestEntity entity = pendingEntity();
        entity.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(repository.findByIdAndClawIdAndOwnerUserId(any(), any(), any())).thenReturn(Optional.of(entity));
        when(repository.save(any(ToolApprovalRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

        assertThatThrownBy(() -> service.requireOwnedPending("claw-1", "approval-1", newPrincipal()))
                .isInstanceOf(ApiException.class)
                .satisfies(exc -> assertThat(((ApiException) exc).status()).isEqualTo(HttpStatus.GONE));
        assertThat(entity.getStatus()).isEqualTo(ToolApprovalStatus.EXPIRED);
    }

    @Test
    void markApprovedUpdatesStatusAndAuditLog() {
        ToolApprovalRequestEntity entity = pendingEntity();
        when(repository.save(any(ToolApprovalRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

        ToolApprovalRequestEntity saved = service.markApprovedForResume(entity, null, newPrincipal());

        assertThat(saved.getStatus()).isEqualTo(ToolApprovalStatus.RESUMING);
        assertThat(saved.getDecision()).isEqualTo(ToolApprovalDecision.APPROVED);
        assertThat(saved.getResolvedBy()).isEqualTo("user-1");
        verify(auditLogService).record(any(), any(), org.mockito.ArgumentMatchers.eq("tool.approval.approved"),
                any(), any(), org.mockito.ArgumentMatchers.eq(true), any());
    }

    @Test
    void markRejectedRecordsReason() {
        ToolApprovalRequestEntity entity = pendingEntity();
        when(repository.save(any(ToolApprovalRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

        ToolApprovalRequestEntity saved = service.markRejectedForResume(entity, "用户取消", null, newPrincipal());

        assertThat(saved.getStatus()).isEqualTo(ToolApprovalStatus.RESUMING);
        assertThat(saved.getDecision()).isEqualTo(ToolApprovalDecision.REJECTED);
        assertThat(saved.getRejectReason()).isEqualTo("用户取消");
    }

    @Test
    void markResumeFailedKeepsDecisionAndMakesRetryable() {
        ToolApprovalRequestEntity entity = pendingEntity();
        entity.setDecision(ToolApprovalDecision.APPROVED);
        when(repository.save(any(ToolApprovalRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

        ToolApprovalRequestEntity saved = service.markResumeFailed(entity, null, "boom");

        assertThat(saved.getStatus()).isEqualTo(ToolApprovalStatus.RESUME_FAILED);
        assertThat(saved.getDecision()).isEqualTo(ToolApprovalDecision.APPROVED);
    }

    private ToolApprovalRequestEntity pendingEntity() {
        ToolApprovalRequestEntity entity = new ToolApprovalRequestEntity();
        entity.setId("approval-1");
        entity.setOwnerUserId("user-1");
        entity.setClawId("claw-1");
        entity.setSessionId("session-1");
        entity.setToolCallId("tc-1");
        entity.setToolName("write_file");
        entity.setRisk("medium");
        entity.setStatus(ToolApprovalStatus.PENDING);
        entity.setIntentSummary("写入文件");
        entity.setArgumentsPreview("{}");
        entity.setPendingStateKey("agent:pending_approval:approval-1");
        entity.setExpiresAt(OffsetDateTime.now().plusMinutes(30));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        return entity;
    }
}
