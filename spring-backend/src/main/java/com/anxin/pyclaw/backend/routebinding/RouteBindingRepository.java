package com.anxin.pyclaw.backend.routebinding;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteBindingRepository extends JpaRepository<RouteBindingEntity, String> {
    List<RouteBindingEntity> findByEnabledTrueOrderByPriorityDescUpdatedAtDesc();
}
