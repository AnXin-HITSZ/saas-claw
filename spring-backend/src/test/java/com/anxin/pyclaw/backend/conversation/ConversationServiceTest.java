package com.anxin.pyclaw.backend.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import com.anxin.pyclaw.backend.claw.ClawEntity;
import com.anxin.pyclaw.backend.claw.ClawRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class ConversationServiceTest {

    private final String CLAW_ID = "claw-1";
    private final String OWNER_ID = "user-a";

    private ConversationRepository conversations;
    private ConversationMessageRepository messages;
    private ClawRepository claws;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        messages = mock(ConversationMessageRepository.class);
        claws = mock(ClawRepository.class);
        service = new ConversationService(conversations, messages, claws);

        ClawEntity claw = new ClawEntity();
        claw.setId(CLAW_ID);
        claw.setOwnerUserId(OWNER_ID);
        when(claws.findById(CLAW_ID)).thenReturn(Optional.of(claw));

        when(conversations.save(any(ConversationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messages.save(any(ConversationMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createsConversationForClawOwner() {
        ConversationEntity conv = service.create(CLAW_ID, "Test Convo", auth(OWNER_ID, false));
        assertThat(conv.getClawId()).isEqualTo(CLAW_ID);
        assertThat(conv.getOwnerUserId()).isEqualTo(OWNER_ID);
        assertThat(conv.getStatus()).isEqualTo("active");
    }

    @Test
    void savesMessageUpdatesConversationTimestamp() {
        ConversationEntity conv = new ConversationEntity();
        conv.setId("conv-1");
        conv.setOwnerUserId(OWNER_ID);
        conv.setClawId(CLAW_ID);
        conv.setTitle("Test");
        conv.setStatus("active");
        conv.setCreatedAt(OffsetDateTime.now());
        conv.setUpdatedAt(OffsetDateTime.now());
        when(conversations.findById("conv-1")).thenReturn(Optional.of(conv));

        ConversationMessageEntity msg = service.saveMessage(
                "conv-1", OWNER_ID, CLAW_ID, "inst-1", "agent-1",
                "k3s", "k3s", "openai", "gpt-4", "user", "hello");

        assertThat(msg.getConversationId()).isEqualTo("conv-1");
        assertThat(msg.getAgentInstanceId()).isEqualTo("inst-1");
        assertThat(msg.getRoleKey()).isEqualTo("k3s");
    }

    @Test
    void oldMessageDefaultsToVisibleInThread() {
        ConversationEntity conv = createConv("conv-1");
        when(conversations.findById("conv-1")).thenReturn(Optional.of(conv));

        // Legacy signature — should default visibleInThread=true
        ConversationMessageEntity msg = service.saveMessage(
                "conv-1", OWNER_ID, CLAW_ID, "inst-1", "agent-1",
                "k3s", "k3s", "openai", "gpt-4", "user", "hello");

        assertThat(msg.isVisibleInThread()).isTrue();
        assertThat(msg.getSortOrder()).isEqualTo(0);
        assertThat(msg.getMessageType()).isEqualTo(MessageType.USER_MESSAGE.name());
    }

    @Test
    void assistantMessageDefaultsToAgentMessageType() {
        ConversationEntity conv = createConv("conv-2");
        when(conversations.findById("conv-2")).thenReturn(Optional.of(conv));

        ConversationMessageEntity msg = service.saveMessage(
                "conv-2", OWNER_ID, CLAW_ID, "inst-1", "agent-1",
                "k3s", "k3s", "openai", "gpt-4", "assistant", "reply");

        assertThat(msg.getMessageType()).isEqualTo(MessageType.AGENT_MESSAGE.name());
    }

    @Test
    void savesWithParentMessageIdChain() {
        ConversationEntity conv = createConv("conv-3");
        when(conversations.findById("conv-3")).thenReturn(Optional.of(conv));

        // Save parent event
        ConversationMessageEntity parent = service.saveMessage(
                "conv-3", OWNER_ID, CLAW_ID, "inst-a", "agent-a",
                "ops", "ops", "openai", "gpt-4", "assistant", "A's analysis",
                null, null, null, true, 0);

        // Save child AGENT_CALL_EVENT (folded)
        ConversationMessageEntity child = service.saveMessage(
                "conv-3", OWNER_ID, CLAW_ID, "inst-a", "agent-a",
                "ops", "ops", "openai", "gpt-4", "assistant", "Called B",
                MessageType.AGENT_CALL_EVENT.name(), parent.getId(),
                "{\"targetAgentInstanceId\":\"inst-b\",\"targetRoleKey\":\"backend\",\"status\":\"COMPLETED\"}",
                false, 0);

        assertThat(child.getParentMessageId()).isEqualTo(parent.getId());
        assertThat(child.getMessageType()).isEqualTo(MessageType.AGENT_CALL_EVENT.name());
        assertThat(child.isVisibleInThread()).isFalse();
        assertThat(child.getMetadataJson()).contains("inst-b");
    }

    @Test
    void metadataJsonStoresAgentCallDetails() {
        ConversationEntity conv = createConv("conv-4");
        when(conversations.findById("conv-4")).thenReturn(Optional.of(conv));

        String metadata = "{\"targetAgentInstanceId\":\"inst-b\",\"targetRoleKey\":\"backend\",\"status\":\"COMPLETED\"}";
        ConversationMessageEntity msg = service.saveMessage(
                "conv-4", OWNER_ID, CLAW_ID, "inst-a", "agent-a",
                "ops", "ops", "openai", "gpt-4", "assistant", "Called B",
                MessageType.AGENT_CALL_EVENT.name(), null, metadata, false, 0);

        assertThat(msg.getMetadataJson()).isEqualTo(metadata);
        assertThat(msg.getMessageType()).isEqualTo(MessageType.AGENT_CALL_EVENT.name());
    }

    @Test
    void sortOrderPreservedForChildEvents() {
        ConversationEntity conv = createConv("conv-5");
        when(conversations.findById("conv-5")).thenReturn(Optional.of(conv));

        ConversationMessageEntity parent = service.saveMessage(
                "conv-5", OWNER_ID, CLAW_ID, "inst-a", "agent-a",
                "ops", "ops", "openai", "gpt-4", "assistant", "A's analysis",
                null, null, null, true, 0);

        ConversationMessageEntity first = service.saveMessage(
                "conv-5", OWNER_ID, CLAW_ID, "inst-a", "agent-a",
                "ops", "ops", "openai", "gpt-4", "assistant", "Step 1",
                MessageType.TOOL_RESULT_DETAIL.name(), parent.getId(), null, false, 0);

        ConversationMessageEntity second = service.saveMessage(
                "conv-5", OWNER_ID, CLAW_ID, "inst-a", "agent-a",
                "ops", "ops", "openai", "gpt-4", "assistant", "Step 2",
                MessageType.TOOL_RESULT_DETAIL.name(), parent.getId(), null, false, 1);

        assertThat(first.getSortOrder()).isEqualTo(0);
        assertThat(second.getSortOrder()).isEqualTo(1);
    }

    private ConversationEntity createConv(String id) {
        ConversationEntity conv = new ConversationEntity();
        conv.setId(id);
        conv.setOwnerUserId(OWNER_ID);
        conv.setClawId(CLAW_ID);
        conv.setTitle("Test");
        conv.setStatus("active");
        conv.setCreatedAt(OffsetDateTime.now());
        conv.setUpdatedAt(OffsetDateTime.now());
        return conv;
    }

    @Test
    void listByUserReturnsUserConversationsOnly() {
        ConversationEntity conv = new ConversationEntity();
        conv.setId("conv-1");
        conv.setOwnerUserId(OWNER_ID);
        conv.setClawId(CLAW_ID);
        conv.setTitle("Test");
        conv.setStatus("active");
        conv.setCreatedAt(OffsetDateTime.now());
        conv.setUpdatedAt(OffsetDateTime.now());
        when(conversations.findByOwnerUserIdOrderByUpdatedAtDesc(OWNER_ID)).thenReturn(List.of(conv));

        List<ConversationEntity> result = service.listByUser(auth(OWNER_ID, false));
        assertThat(result).hasSize(1);
    }

    private Authentication auth(String userId, boolean admin) {
        List<GrantedAuthority> authorities = admin
                ? List.of(new SimpleGrantedAuthority("user:manage"))
                : List.of(new SimpleGrantedAuthority("claw:read"));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(userId, userId, "USER", authorities);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        doReturn(authorities).when(authentication).getAuthorities();
        return authentication;
    }
}
