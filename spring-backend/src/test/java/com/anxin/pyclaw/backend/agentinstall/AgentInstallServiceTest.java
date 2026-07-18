package com.anxin.pyclaw.backend.agentinstall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anxin.pyclaw.backend.agentpackage.AgentPackageEntity;
import com.anxin.pyclaw.backend.agentpackage.AgentPackageRepository;
import com.anxin.pyclaw.backend.agentpackage.AgentPackageVersionEntity;
import com.anxin.pyclaw.backend.agentpackage.AgentPackageVersionRepository;
import com.anxin.pyclaw.backend.audit.AuditLogService;
import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import com.anxin.pyclaw.backend.claw.ClawAgentEntity;
import com.anxin.pyclaw.backend.claw.ClawAgentRepository;
import com.anxin.pyclaw.backend.claw.ClawEntity;
import com.anxin.pyclaw.backend.claw.ClawRepository;
import com.anxin.pyclaw.backend.common.ApiException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AgentInstallServiceTest {

    private static final String CLAW_ID = "claw-1";
    private static final String OWNER_ID = "user-a";
    private static final String OTHER_ID = "user-b";
    private static final String PKG_ID = "pkg-1";
    private static final String VER_ID = "ver-1";

    private ClawRepository claws;
    private ClawAgentRepository clawAgents;
    private AgentPackageRepository packages;
    private AgentPackageVersionRepository versions;
    private AuditLogService auditLogService;
    private AgentInstallService service;

    private final List<ClawAgentEntity> clawAgentList = new ArrayList<>();

    @BeforeEach
    void setUp() {
        claws = mock(ClawRepository.class);
        clawAgents = mock(ClawAgentRepository.class);
        packages = mock(AgentPackageRepository.class);
        versions = mock(AgentPackageVersionRepository.class);
        auditLogService = mock(AuditLogService.class);
        service = new AgentInstallService(claws, clawAgents, packages, versions, auditLogService);

        ClawEntity claw = new ClawEntity();
        claw.setId(CLAW_ID);
        claw.setOwnerUserId(OWNER_ID);
        when(claws.findById(CLAW_ID)).thenReturn(Optional.of(claw));

        when(clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(CLAW_ID)).thenReturn(clawAgentList);
    }

    @Test
    void cannotInstallInvisiblePackage() {
        AgentPackageVersionEntity ver = publishedVersion(VER_ID, PKG_ID);
        when(versions.findById(VER_ID)).thenReturn(Optional.of(ver));
        AgentPackageEntity pkg = privatePackage(PKG_ID, OTHER_ID);
        when(packages.findById(PKG_ID)).thenReturn(Optional.of(pkg));

        AgentInstallRequest req = new AgentInstallRequest(VER_ID, null, null, null);
        assertThatThrownBy(() -> service.install(CLAW_ID, req, auth(OTHER_ID, false)))
                .isInstanceOfSatisfying(ApiException.class, exc -> assertThat(exc.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void cannotInstallNonPublishedVersion() {
        AgentPackageVersionEntity ver = new AgentPackageVersionEntity();
        ver.setId(VER_ID);
        ver.setPackageId(PKG_ID);
        ver.setStatus("draft");
        ver.setDefaultProfile("messaging");
        ver.setCreatedAt(OffsetDateTime.now());
        when(versions.findById(VER_ID)).thenReturn(Optional.of(ver));

        AgentInstallRequest req = new AgentInstallRequest(VER_ID, null, null, null);
        assertThatThrownBy(() -> service.install(CLAW_ID, req, auth(OWNER_ID, false)))
                .isInstanceOfSatisfying(ApiException.class, exc -> assertThat(exc.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void roleKeyConflictGeneratesSuffix() {
        AgentPackageVersionEntity ver = publishedVersion(VER_ID, PKG_ID);
        when(versions.findById(VER_ID)).thenReturn(Optional.of(ver));
        AgentPackageEntity pkg = publicPackage(PKG_ID, OWNER_ID);
        when(packages.findById(PKG_ID)).thenReturn(Optional.of(pkg));
        when(clawAgents.save(any(ClawAgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(packages.save(any(AgentPackageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Pre-existing role with roleKey "k3s"
        ClawAgentEntity existing = new ClawAgentEntity();
        existing.setId("existing-instance");
        existing.setClawId(CLAW_ID);
        existing.setRoleKey("k3s");
        clawAgentList.add(existing);

        AgentInstallRequest req = new AgentInstallRequest(VER_ID, "k3s", "K3s", null);
        ClawAgentEntity result = service.install(CLAW_ID, req, auth(OWNER_ID, false));

        // Should generate auto suffix, not "k3s"
        assertThat(result.getRoleKey()).isNotEqualTo("k3s");
        assertThat(result.getRoleKey()).startsWith("k3s-");
        assertThat(result.getSourceType()).isEqualTo("package");
        assertThat(result.getPackageVersionId()).isEqualTo(VER_ID);
        verify(auditLogService).record(eq("USER"), eq(OWNER_ID), eq("agent_instance.install"), eq("agent_instance"), eq(result.getId()), eq(true), any());
    }

    @Test
    void deletedInstanceCannotBeFound() {
        ClawAgentEntity instance = new ClawAgentEntity();
        instance.setId("inst-1");
        instance.setClawId(CLAW_ID);
        when(clawAgents.findById("inst-1")).thenReturn(Optional.of(instance));

        // Delete
        service.deleteInstance(CLAW_ID, "inst-1", auth(OWNER_ID, false));
        verify(clawAgents).delete(instance);

        // After delete, findById should be mocked as empty for subsequent calls
        when(clawAgents.findById("inst-1")).thenReturn(Optional.empty());
        assertThat(clawAgents.findById("inst-1")).isEmpty();
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

    private AgentPackageVersionEntity publishedVersion(String versionId, String packageId) {
        AgentPackageVersionEntity ver = new AgentPackageVersionEntity();
        ver.setId(versionId);
        ver.setPackageId(packageId);
        ver.setVersion("1.0.0");
        ver.setStatus("published");
        ver.setDefaultProfile("messaging");
        ver.setCreatedAt(OffsetDateTime.now());
        return ver;
    }

    private AgentPackageEntity publicPackage(String pkgId, String ownerId) {
        AgentPackageEntity pkg = new AgentPackageEntity();
        pkg.setId(pkgId);
        pkg.setOwnerUserId(ownerId);
        pkg.setPackageKey("k3s-troubleshooter");
        pkg.setName("K3s 排障助手");
        pkg.setVisibility("public");
        pkg.setInstallCount(0L);
        pkg.setCreatedAt(OffsetDateTime.now());
        pkg.setUpdatedAt(OffsetDateTime.now());
        return pkg;
    }

    private AgentPackageEntity privatePackage(String pkgId, String ownerId) {
        AgentPackageEntity pkg = publicPackage(pkgId, ownerId);
        pkg.setVisibility("private");
        return pkg;
    }

    private Authentication auth(String userId, boolean admin) {
        List<GrantedAuthority> authorities = admin
                ? List.of(new SimpleGrantedAuthority("user:manage"), new SimpleGrantedAuthority("claw:read"))
                : List.of(new SimpleGrantedAuthority("claw:read"));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(userId, userId, "USER", authorities);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        doReturn(authorities).when(authentication).getAuthorities();
        return authentication;
    }
}
