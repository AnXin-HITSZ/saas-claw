package com.clawsaas.claw.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawsaas.claw.client.AgentPackageClient;
import com.clawsaas.claw.client.AuditLogClient;
import com.clawsaas.claw.domain.AgentInstallApprovalEntity;
import com.clawsaas.claw.domain.AgentInstallApprovalStatus;
import com.clawsaas.claw.domain.AuthenticatedPrincipal;
import com.clawsaas.claw.domain.ClawAgentEntity;
import com.clawsaas.claw.domain.ClawEntity;
import com.clawsaas.claw.dto.AgentInstallRequest;
import com.clawsaas.claw.dto.AgentInstancePatchRequest;
import com.clawsaas.claw.dto.AgentPackageInfo;
import com.clawsaas.claw.dto.AgentPackageVersionInfo;
import com.clawsaas.claw.exception.ApiException;
import com.clawsaas.claw.repository.AgentInstallApprovalRepository;
import com.clawsaas.claw.repository.ClawAgentRepository;
import com.clawsaas.claw.repository.ClawRepository;
import com.clawsaas.claw.repository.RouteBindingRepository;
import com.clawsaas.claw.service.AgentInstallService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AgentInstallServiceImplTest {

    private static final String CLAW_ID = "claw-1";
    private static final String OWNER_ID = "user-a";
    private static final String OTHER_ID = "user-b";
    private static final String PKG_ID = "pkg-1";
    private static final String VER_ID = "ver-1";

    private ClawRepository claws;
    private ClawAgentRepository clawAgents;
    private AgentPackageClient agentPackageClient;
    private AuditLogClient auditLogClient;
    private AgentInstallApprovalRepository installApprovals;
    private RouteBindingRepository routeBindings;
    private AgentInstallService service;

    private final List<ClawAgentEntity> clawAgentList = new ArrayList<>();

    @BeforeEach
    void setUp() {
        claws = mock(ClawRepository.class);
        clawAgents = mock(ClawAgentRepository.class);
        agentPackageClient = mock(AgentPackageClient.class);
        auditLogClient = mock(AuditLogClient.class);
        installApprovals = mock(AgentInstallApprovalRepository.class);
        routeBindings = mock(RouteBindingRepository.class);
        service = new AgentInstallServiceImpl(claws, clawAgents, agentPackageClient, auditLogClient, installApprovals, routeBindings);

        ClawEntity claw = new ClawEntity();
        claw.setId(CLAW_ID);
        claw.setOwnerUserId(OWNER_ID);
        when(claws.findById(CLAW_ID)).thenReturn(Optional.of(claw));

        when(clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(CLAW_ID)).thenReturn(clawAgentList);
    }

    @Test
    void cannotInstallInvisiblePackage() {
        when(agentPackageClient.getVersion(VER_ID)).thenReturn(publishedVersion(VER_ID, PKG_ID));
        when(agentPackageClient.getPackage(PKG_ID)).thenReturn(privatePackage(PKG_ID, OTHER_ID));

        AgentInstallRequest req = new AgentInstallRequest(VER_ID, null, null, null);
        assertThatThrownBy(() -> service.install(CLAW_ID, req, auth(OTHER_ID, false)))
                .isInstanceOfSatisfying(ApiException.class, exc -> assertThat(exc.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void cannotInstallNonPublishedVersion() {
        AgentPackageVersionInfo ver = new AgentPackageVersionInfo(VER_ID, PKG_ID, "1.0.0", "draft", "messaging", OffsetDateTime.now(), OffsetDateTime.now());
        when(agentPackageClient.getVersion(VER_ID)).thenReturn(ver);

        AgentInstallRequest req = new AgentInstallRequest(VER_ID, null, null, null);
        assertThatThrownBy(() -> service.install(CLAW_ID, req, auth(OWNER_ID, false)))
                .isInstanceOfSatisfying(ApiException.class, exc -> assertThat(exc.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void roleKeyConflictGeneratesSuffix() {
        when(agentPackageClient.getVersion(VER_ID)).thenReturn(publishedVersion(VER_ID, PKG_ID));
        when(agentPackageClient.getPackage(PKG_ID)).thenReturn(publicPackage(PKG_ID, OWNER_ID));
        when(clawAgents.save(any(ClawAgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Pre-existing role with roleKey "k3s"
        ClawAgentEntity existing = new ClawAgentEntity();
        existing.setId("existing-instance");
        existing.setClawId(CLAW_ID);
        existing.setRoleKey("k3s");
        clawAgentList.add(existing);

        AgentInstallRequest req = new AgentInstallRequest(VER_ID, "k3s", "K3s", null);
        ClawAgentEntity result = service.install(CLAW_ID, req, auth(OWNER_ID, false));

        assertThat(result.getRoleKey()).isNotEqualTo("k3s");
        assertThat(result.getRoleKey()).startsWith("k3s-");
        assertThat(result.getSourceType()).isEqualTo("package");
        assertThat(result.getPackageVersionId()).isEqualTo(VER_ID);
        verify(auditLogClient).record(eq("USER"), eq(OWNER_ID), eq("agent_instance.install"), eq("agent_instance"), eq(result.getId()), eq(true), any());
    }

    @Test
    void deletedInstanceCannotBeFound() {
        ClawAgentEntity instance = new ClawAgentEntity();
        instance.setId("inst-1");
        instance.setClawId(CLAW_ID);
        when(clawAgents.findById("inst-1")).thenReturn(Optional.of(instance));

        service.deleteInstance(CLAW_ID, "inst-1", auth(OWNER_ID, false));
        verify(clawAgents).delete(instance);

        when(clawAgents.findById("inst-1")).thenReturn(Optional.empty());
        assertThat(clawAgents.findById("inst-1")).isEmpty();
    }

    @Test
    void deletingDefaultInstancePromotesNextEnabledRole() {
        ClawAgentEntity deleted = new ClawAgentEntity();
        deleted.setId("inst-default");
        deleted.setClawId(CLAW_ID);
        deleted.setDefaultRole(true);
        deleted.setEnabled(true);

        ClawAgentEntity next = new ClawAgentEntity();
        next.setId("inst-next");
        next.setClawId(CLAW_ID);
        next.setDefaultRole(false);
        next.setEnabled(true);
        next.setUpdatedAt(OffsetDateTime.now());

        clawAgentList.add(deleted);
        clawAgentList.add(next);
        when(clawAgents.findById("inst-default")).thenReturn(Optional.of(deleted));
        when(clawAgents.save(any(ClawAgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deleteInstance(CLAW_ID, "inst-default", auth(OWNER_ID, false));

        assertThat(next.isDefaultRole()).isTrue();
        verify(clawAgents).delete(deleted);
        verify(clawAgents).save(next);
    }

    @Test
    void approveInstallCreatesAgentInstance() {
        AgentInstallApprovalEntity approval = pendingApproval("approval-1", CLAW_ID, OWNER_ID);
        when(installApprovals.findByIdAndClawIdAndOwnerUserId("approval-1", CLAW_ID, OWNER_ID))
                .thenReturn(Optional.of(approval));

        when(agentPackageClient.getVersion(VER_ID)).thenReturn(publishedVersion(VER_ID, PKG_ID));
        when(agentPackageClient.getPackage(PKG_ID)).thenReturn(publicPackage(PKG_ID, OWNER_ID));
        when(clawAgents.save(any(ClawAgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(installApprovals.save(any(AgentInstallApprovalEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ClawAgentEntity result = service.approveInstall(CLAW_ID, "approval-1", auth(OWNER_ID, false));

        assertThat(result).isNotNull();
        assertThat(result.getClawId()).isEqualTo(CLAW_ID);
        assertThat(result.getSourceType()).isEqualTo("package");
        assertThat(approval.getStatus()).isEqualTo(AgentInstallApprovalStatus.CONSUMED.name());
    }

    @Test
    void rejectInstallDoesNotCreateAgentInstance() {
        AgentInstallApprovalEntity approval = pendingApproval("approval-2", CLAW_ID, OWNER_ID);
        when(installApprovals.findByIdAndClawIdAndOwnerUserId("approval-2", CLAW_ID, OWNER_ID))
                .thenReturn(Optional.of(approval));
        when(installApprovals.save(any(AgentInstallApprovalEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.rejectInstall(CLAW_ID, "approval-2", "not needed", auth(OWNER_ID, false));

        assertThat(approval.getStatus()).isEqualTo(AgentInstallApprovalStatus.REJECTED.name());
        verify(clawAgents, never()).save(any(ClawAgentEntity.class));
    }

    @Test
    void duplicateApprovalIsRejected() {
        AgentInstallApprovalEntity approval = consumedApproval("approval-3", CLAW_ID, OWNER_ID);
        when(installApprovals.findByIdAndClawIdAndOwnerUserId("approval-3", CLAW_ID, OWNER_ID))
                .thenReturn(Optional.of(approval));

        assertThatThrownBy(() -> service.approveInstall(CLAW_ID, "approval-3", auth(OWNER_ID, false)))
                .isInstanceOfSatisfying(ApiException.class,
                        exc -> assertThat(exc.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void approvalNotFoundForOtherUser() {
        when(installApprovals.findByIdAndClawIdAndOwnerUserId("approval-4", CLAW_ID, OTHER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveInstall(CLAW_ID, "approval-4", auth(OTHER_ID, false)))
                .isInstanceOfSatisfying(ApiException.class,
                        exc -> assertThat(exc.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void disableInstanceNotReturnedAsEnabled() {
        ClawAgentEntity instance = new ClawAgentEntity();
        instance.setId("inst-1");
        instance.setClawId(CLAW_ID);
        instance.setEnabled(false);
        when(clawAgents.findById("inst-1")).thenReturn(Optional.of(instance));
        when(clawAgents.save(any(ClawAgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentInstancePatchRequest patch = new AgentInstancePatchRequest(null, null, null, null, false, null, null);
        ClawAgentEntity updated = service.updateInstance(CLAW_ID, "inst-1", patch, auth(OWNER_ID, false));

        assertThat(updated.isEnabled()).isFalse();
    }

    private AgentInstallApprovalEntity pendingApproval(String id, String clawId, String ownerId) {
        AgentInstallApprovalEntity a = new AgentInstallApprovalEntity();
        a.setId(id);
        a.setApprovalType("agent_install");
        a.setClawId(clawId);
        a.setOwnerUserId(ownerId);
        a.setPackageId(PKG_ID);
        a.setPackageVersionId(VER_ID);
        a.setReason("need k3s help");
        a.setStatus(AgentInstallApprovalStatus.PENDING.name());
        a.setCreatedAt(OffsetDateTime.now());
        return a;
    }

    private AgentInstallApprovalEntity consumedApproval(String id, String clawId, String ownerId) {
        AgentInstallApprovalEntity a = pendingApproval(id, clawId, ownerId);
        a.setStatus(AgentInstallApprovalStatus.CONSUMED.name());
        a.setResolvedAt(OffsetDateTime.now());
        return a;
    }

    private AgentPackageVersionInfo publishedVersion(String versionId, String packageId) {
        return new AgentPackageVersionInfo(versionId, packageId, "1.0.0", "published", "messaging", OffsetDateTime.now(), OffsetDateTime.now());
    }

    private AgentPackageInfo publicPackage(String pkgId, String ownerId) {
        return new AgentPackageInfo(pkgId, "k3s-troubleshooter", "K3s <<", "public", ownerId, 0, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private AgentPackageInfo privatePackage(String pkgId, String ownerId) {
        return new AgentPackageInfo(pkgId, "k3s-troubleshooter", "K3s <<", "private", ownerId, 0, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private Authentication auth(String userId, boolean admin) {
        List<GrantedAuthority> authorities = admin
                ? List.of(new SimpleGrantedAuthority("user:manage"), new SimpleGrantedAuthority("claw:read"))
                : List.of(new SimpleGrantedAuthority("claw:read"));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(userId, userId, "USER", authorities);
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
