package com.anxin.pyclaw.backend.agentpackage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentPackageVersionRepository extends JpaRepository<AgentPackageVersionEntity, String> {
    Optional<AgentPackageVersionEntity> findByPackageIdAndVersion(String packageId, String version);

    List<AgentPackageVersionEntity> findByPackageIdOrderByCreatedAtDesc(String packageId);

    List<AgentPackageVersionEntity> findByStatusAndPackageIdInOrderByCreatedAtDesc(String status, List<String> packageIds);

    List<AgentPackageVersionEntity> findByStatusOrderByCreatedAtDesc(String status);
}
