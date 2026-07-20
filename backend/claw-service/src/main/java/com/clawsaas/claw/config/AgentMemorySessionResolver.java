package com.clawsaas.claw.config;

import org.springframework.stereotype.Component;

/**
 * Resolves the private memory session ID for a given (conversation, agent instance) pair.
 *
 * Format: agent-memory:{conversation_id}:{agent_instance_id}
 *
 * This ensures each Agent Instance gets its own isolated transcript/history within the
 * OpenClaw Runtime, even when multiple Agents participate in the same Conversation Thread.
 *
 * See ARCHITECTURE.md -- Agent Memory Isolation.
 */
@Component
public class AgentMemorySessionResolver {

    private static final String PREFIX = "agent-memory";

    /**
     * Compute the memory session ID for the given conversation and agent instance.
     * Must not use {@code roleKey} as part of the key (roleKey can be renamed).
     */
    public String resolve(String conversationId, String agentInstanceId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required for agent memory session");
        }
        if (agentInstanceId == null || agentInstanceId.isBlank()) {
            throw new IllegalArgumentException("agentInstanceId is required for agent memory session");
        }
        return PREFIX + ":" + conversationId + ":" + agentInstanceId;
    }
}
