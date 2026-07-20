package com.clawsaas.claw.controller;

import com.clawsaas.claw.dto.SessionDetailResponse;
import com.clawsaas.claw.dto.SessionSummaryResponse;
import com.clawsaas.claw.service.SessionService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private final SessionService service;

    public SessionController(SessionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('agent:run')")
    public List<SessionSummaryResponse> list(
            @RequestParam(required = false) String clawId,
            Authentication authentication) {
        if (clawId != null && !clawId.isBlank()) {
            return service.listByClaw(clawId, authentication);
        }
        return service.listByUser(authentication);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:run')")
    public SessionDetailResponse get(@PathVariable String id, Authentication authentication) {
        return service.getDetail(id, authentication);
    }
}
