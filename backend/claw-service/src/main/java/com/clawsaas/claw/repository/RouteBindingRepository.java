package com.clawsaas.claw.repository;

import com.clawsaas.claw.domain.RouteBindingEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteBindingRepository extends JpaRepository<RouteBindingEntity, String> {
    List<RouteBindingEntity> findByEnabledTrueOrderByPriorityDescUpdatedAtDesc();
}
