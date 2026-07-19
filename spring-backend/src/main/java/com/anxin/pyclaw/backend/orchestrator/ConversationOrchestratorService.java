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
import com.anxin.pyclaw.backend.audit.AuditLogService;
import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.conversation.AgentMemorySessionResolver;
import com.anxin.pyclaw.backend.conversation.ConversationEntity;
import com.anxin.pyclaw.backend.conversation.ConversationMessageEntity;
import com.anxin.pyclaw.backend.conversation.ConversationService;
import com.anxin.pyclaw.backend.conversation.MessageType;
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
    private final AuditLogService auditLogService;

    public ConversationOrchestratorService(
            ClawRepository claws,
            ClawAgentRepository clawAgents,
            ConversationService conversationService,
            AgentMemorySessionResolver memorySessionResolver,
            AgentPackageRepository packages,
            AgentPackageVersionRepository versions,
            AgentInstallApprovalRepository installApprovals,
            @Lazy ClawChatService chatService,
            AuditLogService auditLogService
    ) {
        this.claws = claws;
        this.clawAgents = clawAgents;
        this.conversationService = conversationService;
        this.memorySessionResolver = memorySessionResolver;
        this.packages = packages;
        this.versions = versions;
        this.installApprovals = installApprovals;
        this.chatService = chatService;
        this.auditLogService = auditLogService;
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

        // Resolve calling agent
        ClawAgentEntity calling = clawAgents.findById(request.callingAgentInstanceId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Calling agent instance not found"));

        // Resolve target agent
        ClawAgentEntity target = resolveTargetAgent(request);

        // Resolve conversation
        String conversationId = request.conversationId();
        String ownerUserId = null;
        if (conversationId != null && !conversationId.isBlank()) {
            ConversationEntity conv = conversationService.getConversationInternal(conversationId);
            ownerUserId = conv.getOwnerUserId();
        }

        // Build sharedContext from public conversation thread messages
        String sharedContext = buildSharedContext(request.clawId(), conversationId, calling);

        // Build wrapped prompt for Agent B
        String wrappedPrompt = buildAgentCallPrompt(
                calling.getDisplayName(), calling.getRoleKey(),
                request.message(), sharedContext);

        // Use B's private session (agent-memory:{convId}:{agentBInstanceId})
        // — this is handled by chatService.run() which calls resolveTurnAgent()
        ClawChatRunRequest chatRequest = new ClawChatRunRequest(
                wrappedPrompt,
                target.getRoleKey(),
                null,
                conversationId,
                target.getId()
        );
        ClawChatRunResponse result = chatService.run(request.clawId(), chatRequest, authentication);

        // Save call-chain events (best-effort — errors must not fail the call)
        if (conversationId != null && ownerUserId != null) {
            try {
                saveCallChainEvents(
                        conversationId, ownerUserId, request.clawId(),
                        calling, target, result);
            } catch (Exception ignored) {
                // Event saving is best-effort; the agent call result is already returned
            }
        }

        return result;
    }

    private ClawAgentEntity resolveTargetAgent(OrchestratorCallRequest request) {
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
        return target;
    }

    /**
     * Build sharedContext for nested agent calls.
     * Contains only public conversation thread info — NO private agent memory.
     */
    private String buildSharedContext(String clawId, String conversationId, ClawAgentEntity calling) {
        StringBuilder sb = new StringBuilder();

        // Claw info
        claws.findById(clawId).ifPresent(claw -> {
            sb.append("当前 Claw：\n");
            sb.append("- name: ").append(claw.getName()).append("\n");
            sb.append("- clawId: ").append(claw.getId()).append("\n\n");
        });

        // Conversation info
        if (conversationId != null && !conversationId.isBlank()) {
            try {
                ConversationEntity conv = conversationService.getConversationInternal(conversationId);
                sb.append("当前对话：\n");
                sb.append("- title: ").append(conv.getTitle()).append("\n");
                sb.append("- conversationId: ").append(conv.getId()).append("\n\n");
            } catch (Exception ignored) {
                // conversation may not exist yet
            }
        }

        // Recent public messages (last N=10)
        if (conversationId != null && !conversationId.isBlank()) {
            try {
                List<ConversationMessageEntity> msgs =
                        conversationService.getMessagesInternal(conversationId);
                int total = msgs.size();
                int start = Math.max(0, total - 10);
                if (start < total) {
                    sb.append("最近公开消息：\n");
                    for (int i = start; i < total; i++) {
                        ConversationMessageEntity m = msgs.get(i);
                        String prefix = "user".equalsIgnoreCase(m.getRole()) ? "用户" : "Agent";
                        sb.append(i - start + 1).append(". ")
                                .append(prefix).append("：")
                                .append(truncateText(m.getContent(), 200)).append("\n");
                    }
                }
            } catch (Exception ignored) {
                // best-effort
            }
        }

        return sb.toString();
    }

    /**
     * Build the wrapped prompt for Agent B when called by Agent A.
     */
    private String buildAgentCallPrompt(
            String callerDisplayName, String callerRoleKey,
            String message, String sharedContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("你正在被另一个 Agent 调用。\n\n");

        sb.append("调用来源：\n");
        sb.append("- Agent: ").append(callerDisplayName != null ? callerDisplayName : "未知").append("\n");
        sb.append("- roleKey: ").append(callerRoleKey != null ? callerRoleKey : "未知").append("\n\n");

        sb.append("上游 Agent 委托给你的任务：\n");
        sb.append(message).append("\n\n");

        if (sharedContext != null && !sharedContext.isBlank()) {
            sb.append("共享上下文：\n");
            sb.append(sharedContext);
        }

        return sb.toString();
    }

    /**
     * Save AGENT_CALL_EVENT and TOOL_RESULT_DETAIL as folded (visibleInThread=false) messages.
     */
    private void saveCallChainEvents(
            String conversationId, String ownerUserId, String clawId,
            ClawAgentEntity calling, ClawAgentEntity target,
            ClawChatRunResponse result) {
        // AGENT_CALL_EVENT — folded parent event
        String eventMetadata = "{\"targetAgentInstanceId\":\"" + target.getId()
                + "\",\"targetRoleKey\":\"" + target.getRoleKey()
                + "\",\"status\":\"" + result.status() + "\"}";
        ConversationMessageEntity callEvent = conversationService.saveMessage(
                conversationId, ownerUserId, clawId,
                calling.getId(), calling.getAgentId(), null, calling.getRoleKey(),
                null, null, "assistant",
                "调用了 " + target.getDisplayName() + "：" + target.getRoleKey(),
                MessageType.AGENT_CALL_EVENT.name(), null,
                eventMetadata, false, 0);

        // TOOL_RESULT_DETAIL — B's final reply, folded
        String resultText = result.text() != null ? result.text() : "";
        conversationService.saveMessage(
                conversationId, ownerUserId, clawId,
                target.getId(), target.getAgentId(), null, target.getRoleKey(),
                null, null, "assistant",
                resultText,
                MessageType.TOOL_RESULT_DETAIL.name(), callEvent.getId(),
                null, false, 0);
    }

    private String truncateText(String text, int maxLen) {
        if (text == null) return "";
        String cleaned = text.replace("\n", " ").replace("\r", "");
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen) + "...";
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

    // ---- Internal (FastAPI → Spring) methods ----

    /**
     * Internal discover — validates business rules without requiring a user JWT.
     */
    public List<OrchestratorDiscoverResponse> discoverAgentsInternal(
            OrchestratorDiscoverRequest request, Authentication authentication) {
        requireClawExists(request.clawId());
        auditInternal(authentication, "agent.discover", request.clawId(),
                null, null, null, null, true);
        return discoverAgents(request, authentication);
    }

    /**
     * Internal install request — validates business rules without user JWT.
     */
    public AgentInstallApprovalEntity createInstallRequestInternal(
            OrchestratorInstallRequest request, Authentication authentication) {
        requireClawExists(request.clawId());

        AgentPackageVersionEntity version = versions.findById(request.packageVersionId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Package version not found"));
        if (!VERSION_STATUS_PUBLISHED.equals(version.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Package version is not published");
        }

        // Validate calling agent belongs to claw if provided
        if (request.requestingAgentInstanceId() != null && !request.requestingAgentInstanceId().isBlank()) {
            ClawAgentEntity calling = clawAgents.findById(request.requestingAgentInstanceId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Calling agent instance not found"));
            if (!Objects.equals(calling.getClawId(), request.clawId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Calling agent does not belong to this Claw");
            }
        }

        AgentInstallApprovalEntity result = createInstallRequest(request, authentication);

        auditInternal(authentication, "agent_install.request", request.clawId(),
                null, null, request.packageVersionId(), null, true);
        return result;
    }

    /**
     * Internal call_agent — validates business rules without user JWT.
     */
    public ClawChatRunResponse callAgentInternal(
            OrchestratorCallRequest request, Authentication authentication) {
        requireClawExists(request.clawId());

        // Validate calling agent
        ClawAgentEntity calling = clawAgents.findById(request.callingAgentInstanceId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Calling agent instance not found"));
        if (!Objects.equals(calling.getClawId(), request.clawId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Calling agent does not belong to this Claw");
        }

        // Validate target agent
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

        // Validate conversation belongs to claw if provided (internal — no user auth)
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            ConversationEntity conv = conversationService.getConversationInternal(request.conversationId());
            if (!Objects.equals(conv.getClawId(), request.clawId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Conversation does not belong to this Claw");
            }
        }

        ClawChatRunResponse result = callAgent(request, authentication);

        auditInternal(authentication, "agent.call", request.clawId(),
                request.conversationId(), request.callingAgentInstanceId(),
                null, target.getId(), true);
        return result;
    }

    private void requireClawExists(String clawId) {
        claws.findById(clawId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claw not found"));
    }

    private void auditInternal(Authentication authentication, String action, String clawId,
                                String conversationId, String callingAgentInstanceId,
                                String packageVersionId, String targetAgentInstanceId,
                                boolean success) {
        String actorId = actorId(authentication);
        String actorType = authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal
                ? principal.actorType() : "INTERNAL_SERVICE";
        auditLogService.record(actorType, actorId != null ? actorId : "internal", action,
                "claw", clawId, success, null);
    }
}
