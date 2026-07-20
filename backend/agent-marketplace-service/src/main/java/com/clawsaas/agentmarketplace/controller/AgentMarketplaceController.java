package com.clawsaas.agentmarketplace.controller;

import com.clawsaas.agentmarketplace.dto.AgentPackageResponse;
import com.clawsaas.agentmarketplace.dto.AgentPackageVersionResponse;
import com.clawsaas.agentmarketplace.dto.AgentPublishRequest;
import com.clawsaas.agentmarketplace.service.AgentMarketplaceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent-packages")
public class AgentMarketplaceController {
    private final AgentMarketplaceService service;

    public AgentMarketplaceController(AgentMarketplaceService service) {
        this.service = service;
    }

    @GetMapping
    public List<AgentPackageResponse> list(Authentication authentication) {
        return service.list(authentication);
    }

    @GetMapping("/{packageId}")
    public AgentPackageResponse get(@PathVariable String packageId, Authentication authentication) {
        return service.get(packageId, authentication);
    }

    @PostMapping("/{agentId}/publish")
    public AgentPackageVersionResponse publish(@PathVariable String agentId,
                                                @Valid @RequestBody AgentPublishRequest request,
                                                Authentication authentication) {
        return service.publish(agentId, request, authentication);
    }

    @GetMapping("/{packageId}/versions")
    public List<AgentPackageVersionResponse> listVersions(@PathVariable String packageId, Authentication authentication) {
        return service.listVersions(packageId, authentication);
    }
}
