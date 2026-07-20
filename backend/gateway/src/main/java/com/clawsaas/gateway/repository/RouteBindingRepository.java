package com.clawsaas.gateway.repository;

import com.clawsaas.gateway.entity.RouteBindingEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteBindingRepository extends JpaRepository<RouteBindingEntity, String> {
    List<RouteBindingEntity> findByEnabledTrueOrderByPriorityDescUpdatedAtDesc();
}
