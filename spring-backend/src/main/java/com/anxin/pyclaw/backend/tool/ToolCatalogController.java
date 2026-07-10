package com.anxin.pyclaw.backend.tool;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools")
public class ToolCatalogController {
    private final ToolCatalogService service;

    public ToolCatalogController(ToolCatalogService service) {
        this.service = service;
    }

    @GetMapping("/catalog")
    @PreAuthorize("hasAuthority('tool:catalog:read') or hasAuthority('agent:read')")
    public List<ToolCatalogEntryResponse> catalog() {
        return service.catalog();
    }

    @GetMapping("/profiles")
    @PreAuthorize("hasAuthority('tool:catalog:read') or hasAuthority('agent:read')")
    public List<String> profiles() {
        return service.profiles();
    }

    @PostMapping("/effective")
    @PreAuthorize("hasAuthority('tool:catalog:read') or hasAuthority('agent:read')")
    public EffectiveToolsResponse effective(@RequestBody EffectiveToolsRequest request) {
        return service.effective(request);
    }
}
