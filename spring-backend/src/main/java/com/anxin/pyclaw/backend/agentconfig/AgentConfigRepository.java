package com.anxin.pyclaw.backend.agentconfig;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentConfigRepository extends JpaRepository<AgentConfigEntity, String> {
    Optional<AgentConfigEntity> findByAgentKey(String agentKey);
    Optional<AgentConfigEntity> findByAgentKeyAndEnabledTrue(String agentKey);
}
