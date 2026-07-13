package com.anxin.pyclaw.backend.claw;

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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/claws")
public class ClawController {
    private final ClawService service;

    public ClawController(ClawService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('claw:read')")
    public List<ClawResponse> list(Authentication authentication) {
        return service.list(authentication);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('claw:read')")
    public ClawResponse get(@PathVariable String id, Authentication authentication) {
        return service.get(id, authentication);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('claw:create')")
    public ClawResponse create(@Valid @RequestBody ClawRequest request, Authentication authentication) {
        return service.create(request, authentication);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('claw:update')")
    public ClawResponse update(@PathVariable String id, @Valid @RequestBody ClawRequest request, Authentication authentication) {
        return service.update(id, request, authentication);
    }

    @PostMapping("/{id}/sync-routes")
    @PreAuthorize("hasAuthority('claw:update')")
    public ClawResponse syncRoutes(@PathVariable String id, Authentication authentication) {
        return service.syncRoutes(id, authentication);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('claw:delete')")
    public void delete(@PathVariable String id, Authentication authentication) {
        service.delete(id, authentication);
    }
}
