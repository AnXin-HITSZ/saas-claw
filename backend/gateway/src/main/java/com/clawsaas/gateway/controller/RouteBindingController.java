package com.clawsaas.gateway.controller;

import com.clawsaas.gateway.dto.RouteBindingRequest;
import com.clawsaas.gateway.dto.RouteBindingResponse;
import com.clawsaas.gateway.service.RouteBindingService;
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
@RequestMapping("/api/route-bindings")
public class RouteBindingController {
    private final RouteBindingService service;

    public RouteBindingController(RouteBindingService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('agent:read')")
    public List<RouteBindingResponse> list(Authentication authentication) {
        return service.list(authentication);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('agent:route:manage')")
    public RouteBindingResponse create(@Valid @RequestBody RouteBindingRequest request, Authentication authentication) {
        return service.create(request, authentication);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:route:manage')")
    public RouteBindingResponse update(@PathVariable String id, @Valid @RequestBody RouteBindingRequest request, Authentication authentication) {
        return service.update(id, request, authentication);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:route:manage')")
    public void delete(@PathVariable String id, Authentication authentication) {
        service.delete(id, authentication);
    }
}
