package com.anxin.pyclaw.backend.secret;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/secrets")
public class UserSecretController {
    private final UserSecretService service;

    public UserSecretController(UserSecretService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('claw:read')")
    public List<UserSecretResponse> list(
            @RequestParam(required = false) String clawId,
            Authentication authentication) {
        if (clawId != null && !clawId.isBlank()) {
            return service.listByClaw(clawId, authentication);
        }
        return service.list(authentication);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('claw:update')")
    public UserSecretResponse create(@Valid @RequestBody UserSecretRequest request, Authentication authentication) {
        return service.create(request, authentication);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('claw:update')")
    public UserSecretResponse update(@PathVariable String id, @Valid @RequestBody UserSecretRequest request, Authentication authentication) {
        return service.update(id, request, authentication);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('claw:update')")
    public void delete(@PathVariable String id, Authentication authentication) {
        service.delete(id, authentication);
    }

    @PostMapping("/{id}/sync")
    @PreAuthorize("hasAuthority('claw:update')")
    public UserSecretResponse sync(@PathVariable String id, Authentication authentication) {
        return service.sync(id, authentication);
    }
}
