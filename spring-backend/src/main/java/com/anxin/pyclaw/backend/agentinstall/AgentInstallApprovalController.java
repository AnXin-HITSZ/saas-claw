package com.anxin.pyclaw.backend.agentinstall;

import com.anxin.pyclaw.backend.claw.ClawAgentEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/claws/{clawId}/agent-installs")
public class AgentInstallApprovalController {
    private final AgentInstallService service;

    public AgentInstallApprovalController(AgentInstallService service) {
        this.service = service;
    }

    @PostMapping("/{approvalId}/approve")
    @PreAuthorize("hasAuthority('agent:run')")
    public ClawAgentEntity approve(
            @PathVariable String clawId,
            @PathVariable String approvalId,
            Authentication authentication) {
        return service.approveInstall(clawId, approvalId, authentication);
    }

    @PostMapping("/{approvalId}/reject")
    @PreAuthorize("hasAuthority('agent:run')")
    public void reject(
            @PathVariable String clawId,
            @PathVariable String approvalId,
            @RequestBody(required = false) AgentInstallRejectRequest request,
            Authentication authentication) {
        String reason = request == null ? null : request.reason();
        service.rejectInstall(clawId, approvalId, reason, authentication);
    }

    /**
     * Lightweight reject body — only an optional reason string.
     */
    public record AgentInstallRejectRequest(String reason) {}
}
