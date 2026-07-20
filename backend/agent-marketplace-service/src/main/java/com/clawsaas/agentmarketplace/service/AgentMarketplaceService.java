package com.clawsaas.agentmarketplace.service;

import com.clawsaas.agentmarketplace.dto.AgentPackageResponse;
import com.clawsaas.agentmarketplace.dto.AgentPackageVersionResponse;
import com.clawsaas.agentmarketplace.dto.AgentPublishRequest;
import java.util.List;
import org.springframework.security.core.Authentication;

public interface AgentMarketplaceService {

    List<AgentPackageResponse> list(Authentication authentication);

    AgentPackageResponse get(String packageId, Authentication authentication);

    AgentPackageVersionResponse publish(String agentId, AgentPublishRequest request, Authentication authentication);

    List<AgentPackageVersionResponse> listVersions(String packageId, Authentication authentication);
}
