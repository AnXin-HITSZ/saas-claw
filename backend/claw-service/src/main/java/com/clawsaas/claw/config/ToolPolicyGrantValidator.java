package com.clawsaas.claw.config;

import com.clawsaas.claw.domain.AgentToolPolicyEntity;
import com.clawsaas.claw.dto.AgentToolPolicyRequest;
import com.clawsaas.claw.exception.ApiException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class ToolPolicyGrantValidator {
    private static final List<String> PROFILE_ORDER = List.of("minimal", "readonly", "messaging", "coding", "full");

    public void validate(AgentToolPolicyRequest request, Authentication authentication) {
        String profile = normalizeProfile(request == null ? null : request.profile());
        assertProfileSupported(profile);
    }

    public void requireCanRouteTo(AgentToolPolicyEntity policy, Authentication authentication) {
        String profile = normalizeProfile(policy == null ? null : policy.getProfile());
        assertProfileSupported(profile);
    }

    public String normalizeProfile(String value) {
        String profile = normalize(value);
        return profile == null ? "messaging" : profile;
    }

    private void assertProfileSupported(String profile) {
        if (!PROFILE_ORDER.contains(profile)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported tool profile: " + profile);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }
}
