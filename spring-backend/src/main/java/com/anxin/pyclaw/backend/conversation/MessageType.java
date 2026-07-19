package com.anxin.pyclaw.backend.conversation;

/**
 * Categorizes a conversation_messages row so the frontend can decide
 * whether to render it on the main timeline or inside a folded detail.
 */
public enum MessageType {
    USER_MESSAGE,
    AGENT_MESSAGE,
    SYSTEM_EVENT,
    AGENT_CALL_EVENT,
    TOOL_RESULT_DETAIL,
    TOOL_APPROVAL_CARD
}
