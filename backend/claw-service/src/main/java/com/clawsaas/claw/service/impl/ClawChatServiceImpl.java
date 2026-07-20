package com.clawsaas.claw.service.impl;

import com.clawsaas.claw.domain.AuthenticatedPrincipal;
import com.clawsaas.claw.domain.ClawEntity;
import com.clawsaas.claw.dto.ClawChatRunRequest;
import com.clawsaas.claw.dto.ClawChatRunResponse;
import com.clawsaas.claw.dto.ClawChatSessionResponse;
import com.clawsaas.claw.exception.ApiException;
import com.clawsaas.claw.repository.ClawRepository;
import com.clawsaas.claw.service.ClawChatService;
import com.clawsaas.claw.service.SessionService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * ClawChat service implementation.
 * <p>
 * Currently only CRUD methods (listSessions) are functional.
 * Orchestration methods (run, approve, reject) return 501 NotImplemented.
 * These will be extracted to runtime-service in a future phase.
 * </p>
 */
@Service
public class ClawChatServiceImpl implements ClawChatService {
    private static final Logger log = LoggerFactory.getLogger(ClawChatServiceImpl.class);

    private final ClawRepository claws;
    private final SessionService sessionService;

    public ClawChatServiceImpl(
            ClawRepository claws,
            SessionService sessionService
    ) {
        this.claws = claws;
        this.sessionService = sessionService;
    }

    @Override
    public ClawChatRunResponse run(String clawId, ClawChatRunRequest request, Authentication authentication) {
        // TODO: Extract to runtime-service orchestrator
        // This method previously called: ConversationOrchestratorService, PyclawClient,
        // SandboxClient, ProviderConfigService, AuditLogService, ToolApprovalService,
        // ConversationService, UsageRecordRepository
        log.warn("ClawChatService.run() is not yet implemented in claw-service. " +
                "Orchestration logic belongs in runtime-service.");
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
                "Chat run orchestrator has been extracted to runtime-service. This endpoint is not available in claw-service.");
    }

    @Override
    public ClawChatRunResponse approve(String clawId, String approvalId, Authentication authentication) {
        // TODO: Extract to runtime-service orchestrator
        log.warn("ClawChatService.approve() is not yet implemented in claw-service. " +
                "Approval orchestration logic belongs in runtime-service.");
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
                "Chat approval has been extracted to runtime-service. This endpoint is not available in claw-service.");
    }

    @Override
    public ClawChatRunResponse reject(String clawId, String approvalId, String reason, Authentication authentication) {
        // TODO: Extract to runtime-service orchestrator
        log.warn("ClawChatService.reject() is not yet implemented in claw-service. " +
                "Approval rejection logic belongs in runtime-service.");
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
                "Chat rejection has been extracted to runtime-service. This endpoint is not available in claw-service.");
    }

    @Override
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

    private ClawEntity requireOwnedClaw(String clawId, Authentication authentication) {
        ClawEntity claw = claws.findById(clawId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claw not found"));
        if (!isAdmin(authentication) && !Objects.equals(claw.getOwnerUserId(), actorId(authentication))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Claw not found");
        }
        return claw;
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
