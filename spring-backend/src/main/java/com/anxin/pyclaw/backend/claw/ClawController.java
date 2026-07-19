package com.anxin.pyclaw.backend.claw;

import com.anxin.pyclaw.backend.agentinstall.AgentInstallRequest;
import com.anxin.pyclaw.backend.agentinstall.AgentInstallService;
import com.anxin.pyclaw.backend.agentinstall.AgentInstancePatchRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final AgentInstallService installService;

    public ClawController(ClawService service, AgentInstallService installService) {
        this.service = service;
        this.installService = installService;
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

    // ---- Agent Instance endpoints (ARCHITECTURE.md Task 3) ----

    @PostMapping("/{clawId}/agents/install")
    @PreAuthorize("hasAuthority('claw:update')")
    public ClawRoleResponse installAgent(@PathVariable String clawId, @Valid @RequestBody AgentInstallRequest request, Authentication authentication) {
        return service.toRoleResponsePublic(installService.install(clawId, request, authentication));
    }

    @GetMapping("/{clawId}/agents")
    @PreAuthorize("hasAuthority('claw:read')")
    public List<ClawRoleResponse> listAgents(@PathVariable String clawId, Authentication authentication) {
        return installService.listInstances(clawId, authentication).stream()
                .map(service::toRoleResponsePublic)
                .toList();
    }

    @PatchMapping("/{clawId}/agents/{agentInstanceId}")
    @PreAuthorize("hasAuthority('claw:update')")
    public ClawRoleResponse updateAgent(@PathVariable String clawId, @PathVariable String agentInstanceId, @Valid @RequestBody AgentInstancePatchRequest request, Authentication authentication) {
        return service.toRoleResponsePublic(installService.updateInstance(clawId, agentInstanceId, request, authentication));
    }

    @DeleteMapping("/{clawId}/agents/{agentInstanceId}")
    @PreAuthorize("hasAuthority('claw:update')")
    public void deleteAgent(@PathVariable String clawId, @PathVariable String agentInstanceId, Authentication authentication) {
        installService.deleteInstance(clawId, agentInstanceId, authentication);
    }
}
