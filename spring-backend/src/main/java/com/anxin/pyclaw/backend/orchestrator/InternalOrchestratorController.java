package com.anxin.pyclaw.backend.orchestrator;

import com.anxin.pyclaw.backend.agentinstall.AgentInstallApprovalEntity;
import com.anxin.pyclaw.backend.clawchat.ClawChatRunResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal service-to-service API for FastAPI Runtime → Spring Orchestrator.
 *
 * These endpoints are protected by {@code InternalServiceAuthFilter} which
 * requires a valid {@code pyclaw.runtime.internal-token}. The caller is
 * identified as {@code INTERNAL_SERVICE}, NOT a user principal.
 *
 * Business authorization (claw ownership, agent membership, etc.) is
 * enforced inside {@link ConversationOrchestratorService}.
 */
@RestController
@RequestMapping("/api/internal/orchestrator/agents")
public class InternalOrchestratorController {
    private final ConversationOrchestratorService orchestrator;

    public InternalOrchestratorController(ConversationOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/discover")
    public List<OrchestratorDiscoverResponse> discover(@Valid @RequestBody OrchestratorDiscoverRequest request,
                                                        Authentication authentication) {
        return orchestrator.discoverAgentsInternal(request, authentication);
    }

    @PostMapping("/install-requests")
    public AgentInstallApprovalEntity createInstallRequest(@Valid @RequestBody OrchestratorInstallRequest request,
                                                            Authentication authentication) {
        return orchestrator.createInstallRequestInternal(request, authentication);
    }

    @PostMapping("/call")
    public ClawChatRunResponse callAgent(@Valid @RequestBody OrchestratorCallRequest request,
                                          Authentication authentication) {
        return orchestrator.callAgentInternal(request, authentication);
    }
}
