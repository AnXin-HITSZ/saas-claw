package com.clawsaas.gateway.repository;

import com.clawsaas.gateway.entity.ClawEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Stub repository for Claw.
 * Full repository lives in runtime-service.
 */
public interface ClawRepository extends JpaRepository<ClawEntity, String> {
}
