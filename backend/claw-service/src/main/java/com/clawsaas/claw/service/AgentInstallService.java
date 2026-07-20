package com.clawsaas.claw.service;

import com.clawsaas.claw.domain.ClawAgentEntity;
import com.clawsaas.claw.dto.AgentInstallRequest;
import com.clawsaas.claw.dto.AgentInstancePatchRequest;
import java.util.List;
import org.springframework.security.core.Authentication;

public interface AgentInstallService {

    ClawAgentEntity install(String clawId, AgentInstallRequest request, Authentication authentication);

    List<ClawAgentEntity> listInstances(String clawId, Authentication authentication);

    ClawAgentEntity updateInstance(String clawId, String agentInstanceId, AgentInstancePatchRequest request, Authentication authentication);

    void deleteInstance(String clawId, String agentInstanceId, Authentication authentication);

    ClawAgentEntity approveInstall(String clawId, String approvalId, Authentication authentication);

    void rejectInstall(String clawId, String approvalId, String reason, Authentication authentication);
}
