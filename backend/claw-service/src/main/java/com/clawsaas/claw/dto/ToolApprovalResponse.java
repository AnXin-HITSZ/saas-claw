package com.clawsaas.claw.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Stub record matching approval.ToolApprovalResponse fields.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolApprovalResponse(
        String id,
        String status,
        String decision,
        String clawId,
        String clawName,
        String sessionId,
        String agentId,
        String agentKey,
        String roleKey,
        String toolName,
        String risk,
        String intent,
        Map<String, Object> argumentsPreview,
        String pendingStateKey,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt,
        String rejectReason,
        String executingAgentInstanceId,
        String executingRoleKey,
        String callingAgentInstanceId,
        String callingRoleKey,
        String conversationId
) {
}
