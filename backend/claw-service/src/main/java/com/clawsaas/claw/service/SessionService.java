package com.clawsaas.claw.service;

import com.clawsaas.claw.domain.AuthenticatedPrincipal;
import com.clawsaas.claw.dto.SessionDetailResponse;
import com.clawsaas.claw.dto.SessionSummaryResponse;
import java.util.List;
import org.springframework.security.core.Authentication;

public interface SessionService {

    List<SessionSummaryResponse> listByClaw(String clawId, Authentication authentication);

    List<SessionSummaryResponse> listByUser(Authentication authentication);

    SessionDetailResponse getDetail(String sessionId, Authentication authentication);

    void requireOwnedByClaw(String sessionId, String clawId);

    void requireOwned(String sessionId, AuthenticatedPrincipal principal);

    void saveMessage(String sessionId, String userId, String clawId, String clawName,
                     String agentKey, String provider, String model,
                     String role, String content, long timestamp);

    void saveMessage(String sessionId, String userId, String clawId, String clawName,
                     String agentKey, String roleKey, String agentId,
                     String provider, String model,
                     String role, String content, long timestamp);

    void deleteByClaw(String clawId);
}
