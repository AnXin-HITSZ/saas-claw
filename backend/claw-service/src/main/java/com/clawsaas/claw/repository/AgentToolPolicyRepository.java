package com.clawsaas.claw.repository;

import com.clawsaas.claw.domain.AgentToolPolicyEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentToolPolicyRepository extends JpaRepository<AgentToolPolicyEntity, String> {
    Optional<AgentToolPolicyEntity> findByAgentId(String agentId);
    void deleteByAgentId(String agentId);
}
