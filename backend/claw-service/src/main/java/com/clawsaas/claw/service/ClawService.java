package com.clawsaas.claw.service;

import com.clawsaas.claw.domain.ClawAgentEntity;
import com.clawsaas.claw.dto.ClawRequest;
import com.clawsaas.claw.dto.ClawResponse;
import com.clawsaas.claw.dto.ClawRoleResponse;
import java.util.List;
import org.springframework.security.core.Authentication;

public interface ClawService {

    List<ClawResponse> list(Authentication authentication);

    ClawResponse get(String id, Authentication authentication);

    ClawResponse create(ClawRequest request, Authentication authentication);

    ClawResponse update(String id, ClawRequest request, Authentication authentication);

    ClawResponse syncRoutes(String id, Authentication authentication);

    void delete(String id, Authentication authentication);

    ClawResponse toResponse(com.clawsaas.claw.domain.ClawEntity entity);

    ClawRoleResponse toRoleResponsePublic(ClawAgentEntity entity);
}
