package com.anxin.pyclaw.backend.agentconfig;

import com.anxin.pyclaw.backend.common.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class ToolPolicyGrantValidator {
    private static final List<String> PROFILE_ORDER = List.of("minimal", "readonly", "messaging", "coding", "full");

    public void validate(AgentToolPolicyRequest request, Authentication authentication) {
        Set<String> authorities = authorities(authentication);
        String profile = normalizeProfile(request == null ? null : request.profile());
        if (!canGrantProfile(authorities, profile)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Current user cannot grant tool profile: " + profile);
        }
    }

    public void requireCanRouteTo(AgentToolPolicyEntity policy, Authentication authentication) {
        Set<String> authorities = authorities(authentication);
        String profile = normalizeProfile(policy == null ? null : policy.getProfile());
        if (!canGrantProfile(authorities, profile)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Current user cannot route to agent with tool profile: " + profile);
        }
    }

    public String normalizeProfile(String value) {
        String profile = normalize(value);
        return profile == null ? "messaging" : profile;
    }

    public String normalizeShellApproval(String value) {
        String mode = normalize(value);
        if (mode == null) {
            return "deny";
        }
        if (!Set.of("deny", "require", "auto").contains(mode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported shellApproval: " + value);
        }
        return mode;
    }

    private boolean canGrantProfile(Set<String> authorities, String profile) {
        int requested = PROFILE_ORDER.indexOf(profile);
        if (requested < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported tool profile: " + profile);
        }
        int max = -1;
        for (int i = 0; i < PROFILE_ORDER.size(); i++) {
            if (authorities.contains("tool:grant:" + PROFILE_ORDER.get(i))) {
                max = Math.max(max, i);
            }
        }
        return max >= requested;
    }

    private Set<String> authorities(Authentication authentication) {
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }
}
