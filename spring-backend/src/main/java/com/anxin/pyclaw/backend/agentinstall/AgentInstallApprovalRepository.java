package com.anxin.pyclaw.backend.agentinstall;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentInstallApprovalRepository extends JpaRepository<AgentInstallApprovalEntity, String> {
    List<AgentInstallApprovalEntity> findByClawIdAndStatusOrderByCreatedAtDesc(String clawId, String status);

    List<AgentInstallApprovalEntity> findByOwnerUserIdAndStatusOrderByCreatedAtDesc(String ownerUserId, String status);

    Optional<AgentInstallApprovalEntity> findByIdAndOwnerUserId(String id, String ownerUserId);

    Optional<AgentInstallApprovalEntity> findByIdAndClawIdAndOwnerUserId(String id, String clawId, String ownerUserId);
}
