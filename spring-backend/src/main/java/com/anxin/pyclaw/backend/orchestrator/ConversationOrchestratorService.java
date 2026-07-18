package com.anxin.pyclaw.backend.orchestrator;

import com.anxin.pyclaw.backend.agentinstall.AgentInstallApprovalEntity;
import com.anxin.pyclaw.backend.agentinstall.AgentInstallApprovalRepository;
import com.anxin.pyclaw.backend.agentinstall.AgentInstallApprovalStatus;
import com.anxin.pyclaw.backend.agentpackage.AgentPackageEntity;
import com.anxin.pyclaw.backend.agentpackage.AgentPackageRepository;
import com.anxin.pyclaw.backend.agentpackage.AgentPackageVersionEntity;
import com.anxin.pyclaw.backend.agentpackage.AgentPackageVersionRepository;
import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import com.anxin.pyclaw.backend.claw.ClawAgentEntity;
import com.anxin.pyclaw.backend.claw.ClawAgentRepository;
import com.anxin.pyclaw.backend.claw.ClawEntity;
import com.anxin.pyclaw.backend.claw.ClawRepository;
import com.anxin.pyclaw.backend.clawchat.ClawChatRunRequest;
import com.anxin.pyclaw.backend.clawchat.ClawChatRunResponse;
import com.anxin.pyclaw.backend.clawchat.ClawChatService;
import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.conversation.AgentMemorySessionResolver;
import com.anxin.pyclaw.backend.conversation.ConversationEntity;
import com.anxin.pyclaw.backend.conversation.ConversationService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class ConversationOrchestratorService {

    private static final String VERSION_STATUS_PUBLISHED = "published";
    private static final String VISIBILITY_PUBLIC = "public";
    private static final String APPROVAL_TYPE_AGENT_INSTALL = "agent_install";
    private final ClawRepository claws;
    private final ClawAgentRepository clawAgents;
    private final ConversationService conversationService;
    private final AgentMemorySessionResolver memorySessionResolver;
    private final AgentPackageRepository packages;
    private final AgentPackageVersionRepository versions;
    private final AgentInstallApprovalRepository installApprovals;
    private final ClawChatService chatService;

    public ConversationOrchestratorService(
            ClawRepository claws,
            ClawAgentRepository clawAgents,
            ConversationService conversationService,
            AgentMemorySessionResolver memorySessionResolver,
            AgentPackageRepository packages,
            AgentPackageVersionRepository versions,
            AgentInstallApprovalRepository installApprovals,
            @Lazy ClawChatService chatService
    ) {
        this.claws = claws;
        this.clawAgents = clawAgents;
        this.conversationService = conversationService;
        this.memorySessionResolver = memorySessionResolver;
        this.packages = packages;
        this.versions = versions;
        this.installApprovals = installApprovals;
        this.chatService = chatService;
    }

    /**
     * Resolve the turn's agent, conversation, and runtime session.
     */
    public OrchestratorResult resolveTurnAgent(String clawId, ClawChatRunRequest request, Authentication authentication) {
        ClawEntity claw = requireOwnedClaw(clawId, authentication);
        ConversationEntity conversation = conversationService.getOrCreate(
                request.conversationId(), clawId, authentication);
        ClawAgentEntity instance = resolveAgentInstance(claw, request);
        String runtimeSessionId = memorySessionResolver.resolve(conversation.getId(), instance.getId());

        return new OrchestratorResult(
                conversation.getId(),
                instance.getId(),
                instance.getAgentId(),
                null,
                instance.getRoleKey(),
                instance.getDisplayName(),
                instance.getLocalProfile(),
                runtimeSessionId
        );
    }

    // ---- Agent Discovery ----

    public List<OrchestratorDiscoverResponse> discoverAgents(OrchestratorDiscoverRequest request, Authentication authentication) {
        String actorId = actorId(authentication);
        boolean admin = isAdmin(authentication);

        requireOwnedClaw(request.clawId(), authentication);

        List<AgentPackageVersionEntity> publishedVersions = versions.findByStatusOrderByCreatedAtDesc(
                VERSION_STATUS_PUBLISHED);

        List<OrchestratorDiscoverResponse> results = new ArrayList<>();
        for (AgentPackageVersionEntity ver : publishedVersions) {
            AgentPackageEntity pkg = packages.findById(ver.getPackageId()).orElse(null);
            if (pkg == null) continue;

            if (!admin && !VISIBILITY_PUBLIC.equals(pkg.getVisibility())
                    && !Objects.equals(pkg.getOwnerUserId(), actorId)) {
                continue;
            }

            if (request.query() != null && !request.query().isBlank()) {
                String q = request.query().toLowerCase();
                String haystack = (pkg.getName() + " " + pkg.getSummary() + " " + pkg.getPackageKey()).toLowerCase();
                if (!haystack.contains(q)) continue;
            }

            results.add(new OrchestratorDiscoverResponse(
                    pkg.getId(), ver.getId(), pkg.getPackageKey(),
                    pkg.getName(), pkg.getSummary(), ver.getVersion(),
                    ver.getDefaultProfile(), ver.getCapabilitiesJson()));
        }
        return results;
    }

    // ---- Agent Install Request ----

    public AgentInstallApprovalEntity createInstallRequest(OrchestratorInstallRequest request, Authentication authentication) {
        String actorId = actorId(authentication);
        requireOwnedClaw(request.clawId(), authentication);

        AgentPackageVersionEntity version = versions.findById(request.packageVersionId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Package version not found"));
        if (!VERSION_STATUS_PUBLISHED.equals(version.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Package version is not published");
        }

        OffsetDateTime now = OffsetDateTime.now();
        AgentInstallApprovalEntity approval = new AgentInstallApprovalEntity();
        approval.setId(UUID.randomUUID().toString());
        approval.setApprovalType(APPROVAL_TYPE_AGENT_INSTALL);
        approval.setClawId(request.clawId());
        approval.setOwnerUserId(actorId);
        approval.setRequestingAgentInstanceId(request.requestingAgentInstanceId());
        approval.setPackageId(version.getPackageId());
        approval.setPackageVersionId(request.packageVersionId());
        approval.setReason(request.reason());
        approval.setStatus(AgentInstallApprovalStatus.PENDING.name());
        approval.setCreatedAt(now);
        return installApprovals.save(approval);
    }

    // ---- call_agent ----

    public ClawChatRunResponse callAgent(OrchestratorCallRequest request, Authentication authentication) {
        requireOwnedClaw(request.clawId(), authentication);

        ClawAgentEntity target;
        if (request.targetAgentInstanceId() != null && !request.targetAgentInstanceId().isBlank()) {
            target = clawAgents.findById(request.targetAgentInstanceId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Target agent instance not found"));
        } else if (request.targetRoleKey() != null && !request.targetRoleKey().isBlank()) {
            target = clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(request.clawId()).stream()
                    .filter(r -> request.targetRoleKey().equals(r.getRoleKey()) && r.isEnabled())
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Target role not found or disabled"));
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "targetAgentInstanceId or targetRoleKey is required");
        }

        if (!Objects.equals(target.getClawId(), request.clawId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Cannot call agent in a different Claw");
        }
        if (!target.isEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "Target agent instance is disabled");
        }

        ClawChatRunRequest chatRequest = new ClawChatRunRequest(
                request.message(),
                target.getRoleKey(),
                null,
                request.conversationId(),
                target.getId()
        );
        return chatService.run(request.clawId(), chatRequest, authentication);
    }

    // ---- Helpers ----

    private ClawAgentEntity resolveAgentInstance(ClawEntity claw, ClawChatRunRequest request) {
        List<ClawAgentEntity> roles = clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(claw.getId());
        List<ClawAgentEntity> enabled = roles.stream().filter(ClawAgentEntity::isEnabled).toList();

        if (enabled.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "No enabled agent instance configured for this Claw");
        }

        if (request.agentInstanceId() != null && !request.agentInstanceId().isBlank()) {
            return enabled.stream()
                    .filter(r -> request.agentInstanceId().equals(r.getId()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "Agent instance '" + request.agentInstanceId() + "' not found or disabled in this Claw"));
        }

        if (request.roleKey() != null && !request.roleKey().isBlank()) {
            return enabled.stream()
                    .filter(r -> request.roleKey().equals(r.getRoleKey()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "Role '" + request.roleKey() + "' not found or disabled in this Claw"));
        }

        return enabled.stream().filter(ClawAgentEntity::isDefaultRole).findFirst()
                .orElse(enabled.get(0));
    }

    private ClawEntity requireOwnedClaw(String clawId, Authentication authentication) {
        return claws.findById(clawId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claw not found"));
    }

    private boolean isAdmin(Authentication authentication) {
        Set<String> authorities = authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return authorities.contains("user:manage");
    }

    private String actorId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.userId();
        }
        return null;
    }
}
