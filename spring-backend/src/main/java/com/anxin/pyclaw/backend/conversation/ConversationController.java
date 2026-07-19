package com.anxin.pyclaw.backend.conversation;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping("/{conversationId}/messages")
    @PreAuthorize("hasAuthority('claw:read')")
    public List<ConversationMessageEntity> getMessages(
            @PathVariable String conversationId,
            Authentication authentication) {
        return service.getMessages(conversationId, authentication);
    }
}
