package com.clawsaas.claw.service;

import com.clawsaas.claw.dto.ClawChatRunRequest;
import com.clawsaas.claw.dto.ClawChatRunResponse;
import com.clawsaas.claw.dto.ClawChatSessionResponse;
import java.util.List;
import org.springframework.security.core.Authentication;

public interface ClawChatService {

    /**
     * Run a chat interaction. This method contains orchestration logic that belongs
     * in runtime-service. Currently returns 501 NotImplemented for most paths.
     * TODO: Extract to runtime-service orchestrator.
     */
    ClawChatRunResponse run(String clawId, ClawChatRunRequest request, Authentication authentication);

    /**
     * Approve a pending tool execution. Currently returns 501 NotImplemented.
     * TODO: Extract to runtime-service orchestrator.
     */
    ClawChatRunResponse approve(String clawId, String approvalId, Authentication authentication);

    /**
     * Reject a pending tool execution. Currently returns 501 NotImplemented.
     * TODO: Extract to runtime-service orchestrator.
     */
    ClawChatRunResponse reject(String clawId, String approvalId, String reason, Authentication authentication);

    /**
     * List chat sessions for a Claw. Pure CRUD -- reads from SessionService.
     */
    List<ClawChatSessionResponse> listSessions(String clawId, Authentication authentication);
}
