package com.anxin.pyclaw.backend.clawchat;

import com.anxin.pyclaw.backend.agentconfig.AgentConfigEntity;
import com.anxin.pyclaw.backend.agentconfig.AgentConfigRepository;
import com.anxin.pyclaw.backend.agentconfig.AgentConfigService;
import com.anxin.pyclaw.backend.agentconfig.AgentToolPolicyEntity;
import com.anxin.pyclaw.backend.approval.ToolApprovalRequestEntity;
import com.anxin.pyclaw.backend.approval.ToolApprovalResponse;
import com.anxin.pyclaw.backend.approval.ToolApprovalService;
import com.anxin.pyclaw.backend.audit.AuditLogService;
import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import com.anxin.pyclaw.backend.claw.ClawAgentEntity;
import com.anxin.pyclaw.backend.claw.ClawAgentRepository;
import com.anxin.pyclaw.backend.claw.ClawEntity;
import com.anxin.pyclaw.backend.claw.ClawRepository;
import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.config.PyclawSandboxProperties;
import com.anxin.pyclaw.backend.provider.ProviderConfigEntity;
import com.anxin.pyclaw.backend.provider.ProviderConfigService;
import com.anxin.pyclaw.backend.pyclaw.PyclawAgentResumeRequest;
import com.anxin.pyclaw.backend.pyclaw.PyclawAgentRunRequest;
import com.anxin.pyclaw.backend.pyclaw.PyclawAgentRunResponse;
import com.anxin.pyclaw.backend.pyclaw.PyclawApprovalResponse;
import com.anxin.pyclaw.backend.pyclaw.PyclawClient;
import com.anxin.pyclaw.backend.sandbox.SandboxClient;
import com.anxin.pyclaw.backend.sandbox.SandboxNamingService;
import com.anxin.pyclaw.backend.session.SessionService;
import com.anxin.pyclaw.backend.usage.UsageRecordEntity;
import com.anxin.pyclaw.backend.usage.UsageRecordRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class ClawChatService {
    private static final Logger log = LoggerFactory.getLogger(ClawChatService.class);
    private static final String PENDING_APPROVAL_MESSAGE = "该操作需要你确认后继续执行。";
    private static final String REJECTED_ASSISTANT_MESSAGE = "已根据你的选择取消该工具调用。";
    private static final String FAILED_ASSISTANT_MESSAGE = "[Error] Agent execution failed";

    private final ClawRepository claws;
    private final ClawAgentRepository clawAgents;
    private final AgentConfigRepository agents;
    private final AgentConfigService agentConfigService;
    private final ProviderConfigService providerConfigService;
    private final SessionService sessionService;
    private final SandboxClient sandboxClient;
    private final SandboxNamingService namingService;
    private final PyclawSandboxProperties sandboxProperties;
    private final PyclawClient pyclawClient;
    private final UsageRecordRepository usageRecords;
    private final AuditLogService auditLogService;
    private final ToolApprovalService approvalService;

    public ClawChatService(
            ClawRepository claws,
            ClawAgentRepository clawAgents,
            AgentConfigRepository agents,
            AgentConfigService agentConfigService,
            ProviderConfigService providerConfigService,
            SessionService sessionService,
            SandboxClient sandboxClient,
            SandboxNamingService namingService,
            PyclawSandboxProperties sandboxProperties,
            PyclawClient pyclawClient,
            UsageRecordRepository usageRecords,
            AuditLogService auditLogService,
            ToolApprovalService approvalService
    ) {
        this.claws = claws;
        this.clawAgents = clawAgents;
        this.agents = agents;
        this.agentConfigService = agentConfigService;
        this.providerConfigService = providerConfigService;
        this.sessionService = sessionService;
        this.sandboxClient = sandboxClient;
        this.namingService = namingService;
        this.sandboxProperties = sandboxProperties;
        this.pyclawClient = pyclawClient;
        this.usageRecords = usageRecords;
        this.auditLogService = auditLogService;
        this.approvalService = approvalService;
    }

    // ---- Run Chat ----

    public ClawChatRunResponse run(String clawId, ClawChatRunRequest request, Authentication authentication) {
        AuthenticatedPrincipal principal = requirePrincipal(authentication);
        ClawEntity claw = requireOwnedClaw(clawId, authentication);
        requireClawActive(claw);
        requireRunnerHealthy(claw);

        ResolvedRole resolved = resolveRole(claw, request.roleKey());
        AgentConfigEntity agent = requireAgentAccessible(resolved.agentId(), claw.getOwnerUserId());
        AgentToolPolicyEntity policy = agentConfigService.requirePolicy(agent.getId());
        ProviderConfigEntity provider = providerConfigService.resolveForAgentAndUser(agent, claw.getOwnerUserId());
        if (provider == null) {
            throw new ApiException(HttpStatus.CONFLICT, "No enabled Provider configured for this Agent. Please add a Provider or set it as shared.");
        }

        String model = firstNonBlank(agent.getModel(), provider.getModel());
        String providerName = provider.getName();
        String sessionId = resolveSessionId(request.sessionId(), claw, principal);

        long now = System.currentTimeMillis();

        sessionService.saveMessage(sessionId, principal.userId(), claw.getId(), claw.getName(),
                agent.getAgentKey(), resolved.roleKey(), agent.getId(),
                providerName, model, "user", request.prompt(), now);

        String sandboxBaseUrl = sandboxProperties.isEnabled()
                ? namingService.serviceBaseUrl(claw.getOwnerUserId(), claw.getId())
                : null;

        List<String> toolsAllow = agentConfigService.readListOrNull(policy.getToolsAllowJson());
        List<String> toolsDeny = agentConfigService.readList(policy.getToolsDenyJson());
        List<String> toolsAlsoAllow = agentConfigService.readList(policy.getToolsAlsoAllowJson());
        String systemPrompt = agent.getSystemPrompt();

        PyclawAgentRunRequest pyRequest = new PyclawAgentRunRequest(
                request.prompt(),
                pyclawProvider(provider.getProviderType()),
                sessionId,
                policy.getProfile() != null ? policy.getProfile() : "minimal",
                model,
                provider.getApiMode(),
                provider.getBaseUrl(),
                providerConfigService.getDecryptedApiKey(provider),
                systemPrompt,
                toolsAllow,
                toolsDeny,
                toolsAlsoAllow,
                claw.getId(),
                claw.getOwnerUserId(),
                claw.getName(),
                resolved.roleKey(),
                agent.getAgentKey(),
                sandboxBaseUrl
        );

        long started = System.nanoTime();
        try {
            PyclawAgentRunResponse pyResponse = pyclawClient.runAgent(pyRequest);
            long latencyMs = (System.nanoTime() - started) / 1_000_000;

            if ("PENDING_APPROVAL".equals(pyResponse.status())) {
                return handlePendingApproval(
                        pyResponse, claw, sessionId, agent, resolved, providerName, model,
                        principal, authentication, latencyMs
                );
            }

            return finaliseCompletedRun(
                    pyResponse, claw, sessionId, agent, resolved,
                    providerName, model, principal, authentication, latencyMs, "claw.chat.run"
            );
        } catch (Exception exc) {
            sessionService.saveMessage(sessionId, principal.userId(), claw.getId(), claw.getName(),
                    agent.getAgentKey(), resolved.roleKey(), agent.getId(),
                    providerName, model, "assistant",
                    FAILED_ASSISTANT_MESSAGE, System.currentTimeMillis());
            audit(authentication, "claw.chat.run", claw.getId(), false, exc.getMessage());
            throw exc;
        }
    }

    // ---- Approve / Reject ----

    public ClawChatRunResponse approve(String clawId, String approvalId, Authentication authentication) {
        AuthenticatedPrincipal principal = requirePrincipal(authentication);
        ClawEntity claw = approvalService.requireOwnedClaw(clawId, principal);
        ToolApprovalRequestEntity approval = approvalService.requireOwnedPending(clawId, approvalId, principal);

        ResolvedRole resolved = resolveRoleForApproval(claw, approval.getRoleKey());
        AgentConfigEntity agent = requireAgentAccessible(
                approval.getAgentId() != null ? approval.getAgentId() : resolved.agentId(),
                claw.getOwnerUserId());
        AgentToolPolicyEntity policy = agentConfigService.requirePolicy(agent.getId());
        ProviderConfigEntity provider = providerConfigService.resolveForAgentAndUser(agent, claw.getOwnerUserId());
        if (provider == null) {
            throw new ApiException(HttpStatus.CONFLICT, "No enabled Provider configured for this Agent.");
        }
        approvalService.markApproved(approval, authentication, principal);

        PyclawAgentResumeRequest resumeRequest = buildResumeRequest(
                approval, "APPROVED", null, claw, agent, policy, provider);
        return dispatchResume(claw, approval, resumeRequest, agent, resolved, provider, principal, authentication);
    }

    public ClawChatRunResponse reject(String clawId, String approvalId, String reason, Authentication authentication) {
        AuthenticatedPrincipal principal = requirePrincipal(authentication);
        ClawEntity claw = approvalService.requireOwnedClaw(clawId, principal);
        ToolApprovalRequestEntity approval = approvalService.requireOwnedPending(clawId, approvalId, principal);

        ResolvedRole resolved = resolveRoleForApproval(claw, approval.getRoleKey());
        AgentConfigEntity agent = requireAgentAccessible(
                approval.getAgentId() != null ? approval.getAgentId() : resolved.agentId(),
                claw.getOwnerUserId());
        AgentToolPolicyEntity policy = agentConfigService.requirePolicy(agent.getId());
        ProviderConfigEntity provider = providerConfigService.resolveForAgentAndUser(agent, claw.getOwnerUserId());
        if (provider == null) {
            throw new ApiException(HttpStatus.CONFLICT, "No enabled Provider configured for this Agent.");
        }
        approvalService.markRejected(approval, reason, authentication, principal);

        PyclawAgentResumeRequest resumeRequest = buildResumeRequest(
                approval, "REJECTED", reason, claw, agent, policy, provider);
        return dispatchResume(claw, approval, resumeRequest, agent, resolved, provider, principal, authentication);
    }

    private ClawChatRunResponse dispatchResume(
            ClawEntity claw,
            ToolApprovalRequestEntity approval,
            PyclawAgentResumeRequest resumeRequest,
            AgentConfigEntity agent,
            ResolvedRole resolved,
            ProviderConfigEntity provider,
            AuthenticatedPrincipal principal,
            Authentication authentication
    ) {
        String providerName = provider.getName();
        String model = firstNonBlank(agent.getModel(), provider.getModel());
        long started = System.nanoTime();
        try {
            PyclawAgentRunResponse pyResponse = pyclawClient.resumeAgent(resumeRequest);
            long latencyMs = (System.nanoTime() - started) / 1_000_000;

            approvalService.markConsumed(approval, authentication);

            if ("PENDING_APPROVAL".equals(pyResponse.status())) {
                return handlePendingApproval(
                        pyResponse, claw, approval.getSessionId(), agent, resolved,
                        providerName, model, principal, authentication, latencyMs
                );
            }

            return finaliseCompletedRun(
                    pyResponse, claw, approval.getSessionId(), agent, resolved,
                    providerName, model, principal, authentication, latencyMs, "claw.chat.resume"
            );
        } catch (Exception exc) {
            sessionService.saveMessage(approval.getSessionId(), principal.userId(), claw.getId(), claw.getName(),
                    agent.getAgentKey(), resolved.roleKey(), agent.getId(),
                    providerName, model, "assistant",
                    FAILED_ASSISTANT_MESSAGE, System.currentTimeMillis());
            audit(authentication, "claw.chat.resume", claw.getId(), false, exc.getMessage());
            throw exc;
        }
    }

    private PyclawAgentResumeRequest buildResumeRequest(
            ToolApprovalRequestEntity approval,
            String decision,
            String rejectReason,
            ClawEntity claw,
            AgentConfigEntity agent,
            AgentToolPolicyEntity policy,
            ProviderConfigEntity provider
    ) {
        String sandboxBaseUrl = sandboxProperties.isEnabled()
                ? namingService.serviceBaseUrl(claw.getOwnerUserId(), claw.getId())
                : null;
        String model = firstNonBlank(agent.getModel(), provider.getModel());
        return new PyclawAgentResumeRequest(
                approval.getId(),
                decision,
                rejectReason,
                pyclawProvider(provider.getProviderType()),
                model,
                provider.getApiMode(),
                provider.getBaseUrl(),
                providerConfigService.getDecryptedApiKey(provider),
                agent.getSystemPrompt(),
                policy.getProfile() != null ? policy.getProfile() : "minimal",
                agentConfigService.readListOrNull(policy.getToolsAllowJson()),
                agentConfigService.readList(policy.getToolsDenyJson()),
                agentConfigService.readList(policy.getToolsAlsoAllowJson()),
                sandboxBaseUrl
        );
    }

    private ClawChatRunResponse handlePendingApproval(
            PyclawAgentRunResponse pyResponse,
            ClawEntity claw,
            String sessionId,
            AgentConfigEntity agent,
            ResolvedRole resolved,
            String providerName,
            String model,
            AuthenticatedPrincipal principal,
            Authentication authentication,
            long latencyMs
    ) {
        PyclawApprovalResponse payload = pyResponse.approval();
        ToolApprovalResponse approvalResponse = approvalService.createFromPyclaw(
                claw, sessionId, agent.getId(), agent.getAgentKey(), resolved.roleKey(), payload, authentication);
        String pendingMessage = pyResponse.text() != null && !pyResponse.text().isBlank()
                ? pyResponse.text() : PENDING_APPROVAL_MESSAGE;
        sessionService.saveMessage(sessionId, principal.userId(), claw.getId(), claw.getName(),
                agent.getAgentKey(), resolved.roleKey(), agent.getId(),
                providerName, model, "assistant", pendingMessage, System.currentTimeMillis());
        audit(authentication, "claw.chat.pending_approval", claw.getId(), true, null);
        return new ClawChatRunResponse(
                "PENDING_APPROVAL",
                sessionId, claw.getId(), resolved.roleKey(), agent.getId(), agent.getAgentKey(),
                pendingMessage, null, latencyMs, approvalResponse
        );
    }

    private ClawChatRunResponse finaliseCompletedRun(
            PyclawAgentRunResponse pyResponse,
            ClawEntity claw,
            String sessionId,
            AgentConfigEntity agent,
            ResolvedRole resolved,
            String providerName,
            String model,
            AuthenticatedPrincipal principal,
            Authentication authentication,
            long latencyMs,
            String auditAction
    ) {
        String text = pyResponse.text() != null ? pyResponse.text() : "";
        sessionService.saveMessage(sessionId, principal.userId(), claw.getId(), claw.getName(),
                agent.getAgentKey(), resolved.roleKey(), agent.getId(),
                providerName, model, "assistant",
                text.isBlank() ? REJECTED_ASSISTANT_MESSAGE : text, System.currentTimeMillis());

        recordUsage(principal.userId(), claw.getId(), agent.getId(), agent.getAgentKey(),
                resolved.roleKey(), sessionId, providerName, model, pyResponse, latencyMs);

        audit(authentication, auditAction, claw.getId(), true, null);

        return new ClawChatRunResponse(
                "COMPLETED",
                sessionId, claw.getId(), resolved.roleKey(), agent.getId(), agent.getAgentKey(),
                text, pyResponse.message(), latencyMs, null
        );
    }

    // ---- List Sessions ----

    public List<ClawChatSessionResponse> listSessions(String clawId, Authentication authentication) {
        requireOwnedClaw(clawId, authentication);
        return sessionService.listByClaw(clawId, authentication).stream()
                .map(s -> new ClawChatSessionResponse(
                        s.sessionId(), s.clawId(), s.clawName(),
                        s.agentKey(), null, s.agentKey(),
                        s.provider(), s.model(),
                        s.messageCount(), s.createdAt(), s.lastActiveAt()))
                .toList();
    }

    // ---- Validation helpers ----

    private ClawEntity requireOwnedClaw(String clawId, Authentication authentication) {
        ClawEntity claw = claws.findById(clawId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claw not found"));
        if (!isAdmin(authentication) && !Objects.equals(claw.getOwnerUserId(), actorId(authentication))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Claw not found");
        }
        return claw;
    }

    private void requireClawActive(ClawEntity claw) {
        if (!"active".equals(claw.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Claw is not active (current status: " + claw.getStatus() + ")");
        }
    }

    private void requireRunnerHealthy(ClawEntity claw) {
        if (!sandboxProperties.isEnabled()) {
            return;
        }
        try {
            sandboxClient.healthz(claw.getOwnerUserId(), claw.getId());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Sandbox runner is not ready");
        }
    }

    // ---- Role resolution ----

    private ResolvedRole resolveRole(ClawEntity claw, String requestedRoleKey) {
        List<ClawAgentEntity> roles = clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(claw.getId());
        List<ClawAgentEntity> enabled = roles.stream().filter(ClawAgentEntity::isEnabled).toList();
        if (enabled.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "No enabled agent role configured for this Claw");
        }

        ClawAgentEntity role;
        if (requestedRoleKey != null && !requestedRoleKey.isBlank()) {
            role = enabled.stream()
                    .filter(r -> requestedRoleKey.equals(r.getRoleKey()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Role '" + requestedRoleKey + "' not found in this Claw"));
        } else {
            role = enabled.stream().filter(ClawAgentEntity::isDefaultRole).findFirst()
                    .orElse(enabled.get(0));
        }

        return new ResolvedRole(role.getAgentId(), role.getRoleKey(), role.getDisplayName());
    }

    private ResolvedRole resolveRoleForApproval(ClawEntity claw, String storedRoleKey) {
        try {
            return resolveRole(claw, storedRoleKey);
        } catch (ApiException exc) {
            return resolveRole(claw, null);
        }
    }

    private AgentConfigEntity requireAgentAccessible(String agentId, String ownerUserId) {
        AgentConfigEntity agent = agents.findById(agentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent not found"));
        if (!Objects.equals(agent.getCreatedBy(), ownerUserId)) {
            log.warn("Agent {} createdBy={} does not match claw owner={} — allowing for now",
                    agentId, agent.getCreatedBy(), ownerUserId);
        }
        return agent;
    }

    private String resolveSessionId(String existingSessionId, ClawEntity claw, AuthenticatedPrincipal principal) {
        if (existingSessionId != null && !existingSessionId.isBlank()) {
            sessionService.requireOwned(existingSessionId, principal);
            sessionService.requireOwnedByClaw(existingSessionId, claw.getId());
            return existingSessionId;
        }
        String shortClawId = claw.getId().length() > 8 ? claw.getId().substring(0, 8) : claw.getId();
        return "claw-" + shortClawId + "-" + UUID.randomUUID();
    }

    // ---- Usage ----

    private void recordUsage(String userId, String clawId, String agentId, String agentKey,
                             String roleKey, String sessionId, String provider, String model,
                             PyclawAgentRunResponse response, long latencyMs) {
        UsageRecordEntity entity = new UsageRecordEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        if (response.message() != null) {
            entity.setProvider((String) response.message().get("provider"));
            entity.setModel((String) response.message().get("model"));
            Object usage = response.message().get("usage");
            if (usage instanceof java.util.Map<?, ?> usageMap) {
                entity.setPromptTokens(numberValue(usageMap.get("prompt_tokens")));
                entity.setCompletionTokens(numberValue(usageMap.get("completion_tokens")));
                entity.setTotalTokens(numberValue(usageMap.get("total_tokens")));
            }
        }
        entity.setSuccess(true);
        entity.setLatencyMs(latencyMs);
        entity.setCreatedAt(OffsetDateTime.now());
        usageRecords.save(entity);
    }

    private Long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    // ---- Auth helpers ----

    private AuthenticatedPrincipal requirePrincipal(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    private boolean isAdmin(Authentication authentication) {
        Set<String> authorities = authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        return authorities.contains("user:manage");
    }

    private String actorId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    private String actorType(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.actorType();
        }
        return "UNKNOWN";
    }

    private void audit(Authentication authentication, String action, String resourceId, boolean success, String error) {
        auditLogService.record(actorType(authentication), actorId(authentication), action, "claw", resourceId, success, error);
    }

    private String pyclawProvider(String providerType) {
        return "openai-compatible".equalsIgnoreCase(providerType) ? "openai" : providerType;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private record ResolvedRole(String agentId, String roleKey, String displayName) {
    }
}
