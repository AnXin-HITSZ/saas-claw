package com.clawsaas.agentmarketplace.repository;

import com.clawsaas.agentmarketplace.entity.AgentPackageEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentPackageRepository extends JpaRepository<AgentPackageEntity, String> {
    Optional<AgentPackageEntity> findByOwnerUserIdAndPackageKey(String ownerUserId, String packageKey);

    List<AgentPackageEntity> findByVisibilityOrderByUpdatedAtDesc(String visibility);

    List<AgentPackageEntity> findByOwnerUserIdOrderByUpdatedAtDesc(String ownerUserId);
}
