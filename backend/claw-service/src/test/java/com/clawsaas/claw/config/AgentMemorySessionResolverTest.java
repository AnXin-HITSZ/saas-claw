package com.clawsaas.claw.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentMemorySessionResolverTest {

    private final AgentMemorySessionResolver resolver = new AgentMemorySessionResolver();

    @Test
    void resolvesSessionId() {
        String id = resolver.resolve("conv-abc", "inst-123");
        assertThat(id).isEqualTo("agent-memory:conv-abc:inst-123");
    }

    @Test
    void differentAgentInstancesGetDifferentSessions() {
        String sessionA = resolver.resolve("conv-1", "inst-a");
        String sessionB = resolver.resolve("conv-1", "inst-b");
        assertThat(sessionA).isNotEqualTo(sessionB);
    }

    @Test
    void differentConversationsGetDifferentSessions() {
        String session1 = resolver.resolve("conv-1", "inst-a");
        String session2 = resolver.resolve("conv-2", "inst-a");
        assertThat(session1).isNotEqualTo(session2);
    }

    @Test
    void refusesNullConversationId() {
        assertThatThrownBy(() -> resolver.resolve(null, "inst-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesBlankConversationId() {
        assertThatThrownBy(() -> resolver.resolve("  ", "inst-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesNullAgentInstanceId() {
        assertThatThrownBy(() -> resolver.resolve("conv-1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
