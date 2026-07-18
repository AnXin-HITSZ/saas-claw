package com.anxin.pyclaw.backend.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import com.anxin.pyclaw.backend.claw.ClawAgentEntity;
import com.anxin.pyclaw.backend.claw.ClawAgentRepository;
import com.anxin.pyclaw.backend.claw.ClawEntity;
import com.anxin.pyclaw.backend.claw.ClawRepository;
import com.anxin.pyclaw.backend.clawchat.ClawChatRunRequest;
import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.conversation.AgentMemorySessionResolver;
import com.anxin.pyclaw.backend.conversation.ConversationEntity;
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

class ConversationOrchestratorServiceTest {

    private final String CLAW_ID = "claw-1";
    private final String OWNER_ID = "user-a";

    private ClawRepository claws;
    private ClawAgentRepository clawAgents;
    private ConversationService conversationService;
    private AgentMemorySessionResolver memorySessionResolver;
    private ConversationOrchestratorService orchestrator;

    private final List<ClawAgentEntity> instances = new ArrayList<>();

    @BeforeEach
    void setUp() {
        claws = mock(ClawRepository.class);
        clawAgents = mock(ClawAgentRepository.class);
        conversationService = mock(ConversationService.class);
        memorySessionResolver = new AgentMemorySessionResolver();

        ClawEntity claw = new ClawEntity();
        claw.setId(CLAW_ID);
        claw.setOwnerUserId(OWNER_ID);
        when(claws.findById(CLAW_ID)).thenReturn(Optional.of(claw));
        when(clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(CLAW_ID)).thenReturn(instances);

        ConversationEntity conv = new ConversationEntity();
        conv.setId("conv-1");
        conv.setClawId(CLAW_ID);
        conv.setOwnerUserId(OWNER_ID);
        conv.setTitle("Test");
        conv.setStatus("active");
        conv.setCreatedAt(OffsetDateTime.now());
        conv.setUpdatedAt(OffsetDateTime.now());
        when(conversationService.getOrCreate(any(), any(), any())).thenReturn(conv);

        orchestrator = new ConversationOrchestratorService(claws, clawAgents, conversationService, memorySessionResolver,
                null, null, null, null);
    }

    @Test
    void explicitAgentInstanceIdTakesPriority() {
        ClawAgentEntity instanceB = addInstance("inst-b", "role-b", false);
        ClawAgentEntity instanceA = addInstance("inst-a", "role-a", false);

        ClawChatRunRequest req = new ClawChatRunRequest("hello", null, null, null, "inst-a");
        OrchestratorResult result = orchestrator.resolveTurnAgent(CLAW_ID, req, auth(OWNER_ID, false));

        assertThat(result.agentInstanceId()).isEqualTo("inst-a");
        assertThat(result.roleKey()).isEqualTo("role-a");
    }

    @Test
    void roleKeyResolvesToAgentInstance() {
        addInstance("inst-1", "k3s", false);

        ClawChatRunRequest req = new ClawChatRunRequest("hello", "k3s", null, null, null);
        OrchestratorResult result = orchestrator.resolveTurnAgent(CLAW_ID, req, auth(OWNER_ID, false));

        assertThat(result.roleKey()).isEqualTo("k3s");
        assertThat(result.agentInstanceId()).isEqualTo("inst-1");
    }

    @Test
    void disabledInstanceCannotBeSelected() {
        addInstance("inst-enabled", "enabled-role", false); // keeps enabled list non-empty
        ClawAgentEntity disabled = addInstance("inst-disabled", "disabled-role", false);
        disabled.setEnabled(false);

        // roleKey "disabled-role" exists but is disabled — not found in enabled list
        ClawChatRunRequest req = new ClawChatRunRequest("hello", "disabled-role", null, null, null);
        assertThatThrownBy(() -> orchestrator.resolveTurnAgent(CLAW_ID, req, auth(OWNER_ID, false)))
                .isInstanceOfSatisfying(ApiException.class,
                        exc -> assertThat(exc.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void defaultRoleIsFallback() {
        addInstance("inst-other", "other", false);
        addInstance("inst-default", "default", true);

        ClawChatRunRequest req = new ClawChatRunRequest("hello", null, null, null, null);
        OrchestratorResult result = orchestrator.resolveTurnAgent(CLAW_ID, req, auth(OWNER_ID, false));

        assertThat(result.agentInstanceId()).isEqualTo("inst-default");
    }

    @Test
    void memorySessionUsesStableAgentInstanceId() {
        addInstance("inst-stable", "k3s", false);

        ClawChatRunRequest req = new ClawChatRunRequest("hello", "k3s", null, null, null);
        OrchestratorResult result = orchestrator.resolveTurnAgent(CLAW_ID, req, auth(OWNER_ID, false));

        assertThat(result.runtimeSessionId()).isEqualTo("agent-memory:conv-1:inst-stable");
    }

    private ClawAgentEntity addInstance(String id, String roleKey, boolean defaultRole) {
        ClawAgentEntity inst = new ClawAgentEntity();
        inst.setId(id);
        inst.setClawId(CLAW_ID);
        inst.setAgentId("agent-" + id);
        inst.setRoleKey(roleKey);
        inst.setDisplayName("Display " + roleKey);
        inst.setDefaultRole(defaultRole);
        inst.setEnabled(true);
        inst.setSortOrder(instances.size());
        inst.setCreatedAt(OffsetDateTime.now());
        inst.setUpdatedAt(OffsetDateTime.now());
        instances.add(inst);
        return inst;
    }

    private Authentication auth(String userId, boolean admin) {
        List<GrantedAuthority> authorities = admin
                ? List.of(new SimpleGrantedAuthority("user:manage"))
                : List.of(new SimpleGrantedAuthority("claw:read"));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(userId, userId, "USER", authorities);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        doReturn(authorities).when(authentication).getAuthorities();
        return authentication;
    }
}
