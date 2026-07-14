package com.anxin.pyclaw.backend.secret;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSecretRepository extends JpaRepository<UserSecretEntity, String> {
    List<UserSecretEntity> findByOwnerUserIdOrderByUpdatedAtDesc(String ownerUserId);
    List<UserSecretEntity> findByOwnerUserIdAndClawIdOrderByUpdatedAtDesc(String ownerUserId, String clawId);
    List<UserSecretEntity> findByClawIdAndEnabledTrue(String clawId);
}
