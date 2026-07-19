package com.anxin.pyclaw.backend.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anxin.pyclaw.backend.agentinstall.AgentInstallApprovalEntity;
import com.anxin.pyclaw.backend.agentinstall.AgentInstallApprovalRepository;
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
import com.anxin.pyclaw.backend.clawchat.ClawChatRunResponse;
import com.anxin.pyclaw.backend.clawchat.ClawChatService;
import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.conversation.AgentMemorySessionResolver;
import com.anxin.pyclaw.backend.conversation.ConversationService;
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

class OrchestratorApiTest {

    private static final String CLAW_ID = "claw-1";
    private static final String OWNER_ID = "user-a";
    private static final String PKG_ID = "pkg-1";
    private static final String VER_ID = "ver-1";

    private ClawRepository claws;
    private ClawAgentRepository clawAgents;
    private ConversationService conversationService;
    private AgentMemorySessionResolver memorySessionResolver;
    private AgentPackageRepository packages;
    private AgentPackageVersionRepository versions;
    private AgentInstallApprovalRepository installApprovals;
    private ClawChatService chatService;
    private ConversationOrchestratorService orchestrator;

    private final List<ClawAgentEntity> agents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        claws = mock(ClawRepository.class);
        clawAgents = mock(ClawAgentRepository.class);
        conversationService = mock(ConversationService.class);
        memorySessionResolver = new AgentMemorySessionResolver();
        packages = mock(AgentPackageRepository.class);
        versions = mock(AgentPackageVersionRepository.class);
        installApprovals = mock(AgentInstallApprovalRepository.class);
        chatService = mock(ClawChatService.class);

        ClawEntity claw = new ClawEntity();
        claw.setId(CLAW_ID);
        claw.setOwnerUserId(OWNER_ID);
        when(claws.findById(CLAW_ID)).thenReturn(Optional.of(claw));
        when(clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(CLAW_ID)).thenReturn(agents);

        AuditLogService auditLogService = mock(AuditLogService.class);
        orchestrator = new ConversationOrchestratorService(
                claws, clawAgents, conversationService, memorySessionResolver,
                packages, versions, installApprovals, chatService, auditLogService);
    }

    @Test
    void discoverAgentsReturnsPublicPackages() {
        AgentPackageVersionEntity ver = publishedVersion(VER_ID, PKG_ID);
        when(versions.findByStatusOrderByCreatedAtDesc("published")).thenReturn(List.of(ver));
        AgentPackageEntity pkg = new AgentPackageEntity();
        pkg.setId(PKG_ID);
        pkg.setOwnerUserId("other-user");
        pkg.setPackageKey("k3s");
        pkg.setName("K3s Helper");
        pkg.setVisibility("public");
        pkg.setInstallCount(0L);
        pkg.setCreatedAt(OffsetDateTime.now());
        pkg.setUpdatedAt(OffsetDateTime.now());
        when(packages.findById(PKG_ID)).thenReturn(Optional.of(pkg));

        OrchestratorDiscoverRequest req = new OrchestratorDiscoverRequest(CLAW_ID, null, null, null);
        List<OrchestratorDiscoverResponse> results = orchestrator.discoverAgents(req, auth(OWNER_ID, false));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).packageKey()).isEqualTo("k3s");
    }

    @Test
    void discoverAgentsHidesPrivatePackagesFromOthers() {
        AgentPackageVersionEntity ver = publishedVersion(VER_ID, PKG_ID);
        when(versions.findByStatusOrderByCreatedAtDesc("published")).thenReturn(List.of(ver));
        AgentPackageEntity pkg = new AgentPackageEntity();
        pkg.setId(PKG_ID);
        pkg.setOwnerUserId("other-user");
        pkg.setPackageKey("secret");
        pkg.setName("Secret Agent");
        pkg.setVisibility("private");
        pkg.setInstallCount(0L);
        pkg.setCreatedAt(OffsetDateTime.now());
        pkg.setUpdatedAt(OffsetDateTime.now());
        when(packages.findById(PKG_ID)).thenReturn(Optional.of(pkg));

        OrchestratorDiscoverRequest req = new OrchestratorDiscoverRequest(CLAW_ID, null, null, null);
        List<OrchestratorDiscoverResponse> results = orchestrator.discoverAgents(req, auth(OWNER_ID, false));

        assertThat(results).isEmpty();
    }

    @Test
    void installRequestCreatesPendingApproval() {
        AgentPackageVersionEntity ver = publishedVersion(VER_ID, PKG_ID);
        when(versions.findById(VER_ID)).thenReturn(Optional.of(ver));

        AgentInstallApprovalEntity saved = new AgentInstallApprovalEntity();
        saved.setId("approval-1");
        saved.setStatus("PENDING");
        saved.setApprovalType("agent_install");
        when(installApprovals.save(any(AgentInstallApprovalEntity.class))).thenReturn(saved);

        OrchestratorInstallRequest req = new OrchestratorInstallRequest(CLAW_ID, VER_ID, null, "need k3s help");
        AgentInstallApprovalEntity result = orchestrator.createInstallRequest(req, auth(OWNER_ID, false));

        assertThat(result.getId()).isEqualTo("approval-1");
        assertThat(result.getStatus()).isEqualTo("PENDING");
    }

    private ClawAgentEntity mockCallingAgent() {
        ClawAgentEntity calling = new ClawAgentEntity();
        calling.setId("caller-inst");
        calling.setClawId(CLAW_ID);
        calling.setRoleKey("ops");
        calling.setDisplayName("Ops Agent");
        calling.setEnabled(true);
        calling.setAgentId("agent-caller");
        when(clawAgents.findById("caller-inst")).thenReturn(Optional.of(calling));
        return calling;
    }

    @Test
    void callAgentRejectsAgentInDifferentClaw() {
        mockCallingAgent();
        ClawAgentEntity target = new ClawAgentEntity();
        target.setId("inst-1");
        target.setClawId("different-claw");
        target.setRoleKey("k3s");
        target.setEnabled(true);
        when(clawAgents.findById("inst-1")).thenReturn(Optional.of(target));
        when(clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(CLAW_ID)).thenReturn(List.of(target));

        OrchestratorCallRequest req = new OrchestratorCallRequest(
                CLAW_ID, "caller-inst", "inst-1", null, "hello", null);

        assertThatThrownBy(() -> orchestrator.callAgent(req, auth(OWNER_ID, false)))
                .isInstanceOfSatisfying(ApiException.class,
                        exc -> assertThat(exc.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void callAgentRejectsDisabledAgent() {
        mockCallingAgent();
        ClawAgentEntity target = new ClawAgentEntity();
        target.setId("inst-1");
        target.setClawId(CLAW_ID);
        target.setRoleKey("k3s");
        target.setEnabled(false);
        when(clawAgents.findById("inst-1")).thenReturn(Optional.of(target));

        OrchestratorCallRequest req = new OrchestratorCallRequest(
                CLAW_ID, "caller-inst", "inst-1", null, "hello", null);

        assertThatThrownBy(() -> orchestrator.callAgent(req, auth(OWNER_ID, false)))
                .isInstanceOfSatisfying(ApiException.class,
                        exc -> assertThat(exc.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void callAgentDelegatesToClawChatService() {
        mockCallingAgent();
        ClawAgentEntity target = new ClawAgentEntity();
        target.setId("inst-1");
        target.setClawId(CLAW_ID);
        target.setDisplayName("K3s Helper");
        target.setRoleKey("k3s");
        target.setEnabled(true);
        target.setAgentId("agent-config-1");
        when(clawAgents.findById("inst-1")).thenReturn(Optional.of(target));
        when(clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(CLAW_ID)).thenReturn(List.of(target));

        // Mock conversation
        com.anxin.pyclaw.backend.conversation.ConversationEntity conv =
                new com.anxin.pyclaw.backend.conversation.ConversationEntity();
        conv.setId("conv-1");
        conv.setClawId(CLAW_ID);
        conv.setOwnerUserId(OWNER_ID);
        conv.setTitle("Test");
        conv.setStatus("active");
        conv.setCreatedAt(OffsetDateTime.now());
        conv.setUpdatedAt(OffsetDateTime.now());
        when(conversationService.getConversationInternal("conv-1")).thenReturn(conv);
        when(conversationService.getMessagesInternal("conv-1")).thenReturn(List.of());

        ClawChatRunResponse mockResp = new ClawChatRunResponse(
                "COMPLETED", "session-1", CLAW_ID, "k3s", "agent-config-1", "k3s-agent",
                "hello from k3s", null, 100L, null, "conv-1", "inst-1");
        when(chatService.run(anyString(), any(), any())).thenReturn(mockResp);

        OrchestratorCallRequest req = new OrchestratorCallRequest(
                CLAW_ID, "caller-inst", "inst-1", null, "hello from caller", "conv-1");
        ClawChatRunResponse result = orchestrator.callAgent(req, auth(OWNER_ID, false));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.agentInstanceId()).isEqualTo("inst-1");
    }

    private AgentPackageVersionEntity publishedVersion(String verId, String pkgId) {
        AgentPackageVersionEntity ver = new AgentPackageVersionEntity();
        ver.setId(verId);
        ver.setPackageId(pkgId);
        ver.setVersion("1.0.0");
        ver.setStatus("published");
        ver.setDefaultProfile("messaging");
        ver.setCreatedAt(OffsetDateTime.now());
        return ver;
    }

    // ---- Internal (FastAPI → Spring) API tests ----

    @Test
    void internalDiscoverReturnsPublicPackages() {
        AgentPackageVersionEntity ver = publishedVersion(VER_ID, PKG_ID);
        when(versions.findByStatusOrderByCreatedAtDesc("published")).thenReturn(List.of(ver));
        AgentPackageEntity pkg = new AgentPackageEntity();
        pkg.setId(PKG_ID);
        pkg.setOwnerUserId(OWNER_ID);
        pkg.setPackageKey("k3s");
        pkg.setName("K3s Helper");
        pkg.setVisibility("public");
        pkg.setInstallCount(0L);
        pkg.setCreatedAt(OffsetDateTime.now());
        pkg.setUpdatedAt(OffsetDateTime.now());
        when(packages.findById(PKG_ID)).thenReturn(Optional.of(pkg));

        OrchestratorDiscoverRequest req = new OrchestratorDiscoverRequest(CLAW_ID, null, null, null);
        List<OrchestratorDiscoverResponse> results = orchestrator.discoverAgentsInternal(req, internalAuth());

        assertThat(results).hasSize(1);
    }

    @Test
    void internalDiscoverRejectsNonexistentClaw() {
        OrchestratorDiscoverRequest req = new OrchestratorDiscoverRequest("nonexistent", null, null, null);

        assertThatThrownBy(() -> orchestrator.discoverAgentsInternal(req, internalAuth()))
                .isInstanceOfSatisfying(ApiException.class,
                        exc -> assertThat(exc.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void internalCallAgentValidatesCallingAgentBelongsToClaw() {
        ClawAgentEntity calling = new ClawAgentEntity();
        calling.setId("caller-inst");
        calling.setClawId("different-claw");
        calling.setRoleKey("ops");
        calling.setEnabled(true);
        when(clawAgents.findById("caller-inst")).thenReturn(Optional.of(calling));

        OrchestratorCallRequest req = new OrchestratorCallRequest(
                CLAW_ID, "caller-inst", "target-inst", null, "hello", null);

        assertThatThrownBy(() -> orchestrator.callAgentInternal(req, internalAuth()))
                .isInstanceOfSatisfying(ApiException.class,
                        exc -> assertThat(exc.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void internalCallAgentRejectsDisabledTarget() {
        ClawAgentEntity calling = new ClawAgentEntity();
        calling.setId("caller-inst");
        calling.setClawId(CLAW_ID);
        calling.setRoleKey("ops");
        calling.setEnabled(true);
        when(clawAgents.findById("caller-inst")).thenReturn(Optional.of(calling));

        ClawAgentEntity target = new ClawAgentEntity();
        target.setId("target-inst");
        target.setClawId(CLAW_ID);
        target.setRoleKey("k3s");
        target.setEnabled(false);
        when(clawAgents.findById("target-inst")).thenReturn(Optional.of(target));
        when(clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(CLAW_ID)).thenReturn(List.of(target));

        OrchestratorCallRequest req = new OrchestratorCallRequest(
                CLAW_ID, "caller-inst", "target-inst", null, "hello", null);

        assertThatThrownBy(() -> orchestrator.callAgentInternal(req, internalAuth()))
                .isInstanceOfSatisfying(ApiException.class,
                        exc -> assertThat(exc.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void internalCallAgentDelegatesToChatService() {
        ClawAgentEntity calling = new ClawAgentEntity();
        calling.setId("caller-inst");
        calling.setClawId(CLAW_ID);
        calling.setRoleKey("ops");
        calling.setDisplayName("Ops Agent");
        calling.setEnabled(true);
        calling.setAgentId("agent-caller");
        when(clawAgents.findById("caller-inst")).thenReturn(Optional.of(calling));

        ClawAgentEntity target = new ClawAgentEntity();
        target.setId("target-inst");
        target.setClawId(CLAW_ID);
        target.setRoleKey("k3s");
        target.setDisplayName("K3s Helper");
        target.setEnabled(true);
        target.setAgentId("agent-config-1");
        when(clawAgents.findById("target-inst")).thenReturn(Optional.of(target));
        when(clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(CLAW_ID)).thenReturn(List.of(target));

        // Mock conversation for sharedContext
        com.anxin.pyclaw.backend.conversation.ConversationEntity conv =
                new com.anxin.pyclaw.backend.conversation.ConversationEntity();
        conv.setId("conv-1");
        conv.setClawId(CLAW_ID);
        conv.setOwnerUserId(OWNER_ID);
        conv.setTitle("Test");
        conv.setStatus("active");
        conv.setCreatedAt(OffsetDateTime.now());
        conv.setUpdatedAt(OffsetDateTime.now());
        when(conversationService.getConversationInternal("conv-1")).thenReturn(conv);
        when(conversationService.getMessagesInternal("conv-1")).thenReturn(List.of());

        ClawChatRunResponse mockResp = new ClawChatRunResponse(
                "COMPLETED", "session-1", CLAW_ID, "k3s", "agent-config-1", "k3s-agent",
                "hello from k3s", null, 100L, null, "conv-1", "target-inst");
        when(chatService.run(anyString(), any(), any())).thenReturn(mockResp);

        OrchestratorCallRequest req = new OrchestratorCallRequest(
                CLAW_ID, "caller-inst", "target-inst", null, "hello from caller", "conv-1");
        ClawChatRunResponse result = orchestrator.callAgentInternal(req, internalAuth());

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.agentInstanceId()).isEqualTo("target-inst");
    }

    private Authentication internalAuth() {
        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("agent:run"),
                new SimpleGrantedAuthority("agent:read"));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "internal", "internal-service", "INTERNAL_SERVICE", authorities);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        return authentication;
    }

    private Authentication auth(String userId, boolean admin) {
        List<GrantedAuthority> authorities = admin
                ? List.of(new SimpleGrantedAuthority("user:manage"))
                : List.of(new SimpleGrantedAuthority("claw:read"));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(userId, userId, "USER", authorities);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        return authentication;
    }
}
