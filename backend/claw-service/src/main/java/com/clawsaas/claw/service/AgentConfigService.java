package com.clawsaas.claw.service;

import com.clawsaas.claw.domain.AgentConfigEntity;
import com.clawsaas.claw.domain.AgentToolPolicyEntity;
import com.clawsaas.claw.dto.AgentConfigRequest;
import com.clawsaas.claw.dto.AgentConfigResponse;
import com.clawsaas.claw.dto.AgentToolPolicyResponse;
import java.util.List;
import org.springframework.security.core.Authentication;

public interface AgentConfigService {

    List<AgentConfigResponse> list(Authentication authentication);

    AgentConfigResponse get(String id, Authentication authentication);

    AgentConfigResponse create(AgentConfigRequest request, Authentication authentication);

    AgentConfigResponse update(String id, AgentConfigRequest request, Authentication authentication);

    void delete(String id, Authentication authentication);

    AgentConfigEntity requireEnabledByKey(String agentKey);

    AgentToolPolicyEntity requirePolicy(String agentId);

    AgentConfigResponse toResponse(AgentConfigEntity entity);

    AgentToolPolicyResponse toPolicyResponse(AgentToolPolicyEntity entity);

    List<String> readList(String json);

    List<String> readListOrNull(String json);
}
