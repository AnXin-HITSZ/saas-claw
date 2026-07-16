package com.anxin.pyclaw.backend.auth;

import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.sandbox.SandboxOrchestratorService;
import com.anxin.pyclaw.backend.user.UserEntity;
import com.anxin.pyclaw.backend.user.UserRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private static final String DEFAULT_REGISTER_AUTHORITIES = "claw:read,claw:create,claw:update,claw:delete,agent:run,agent:read,agent:create,agent:update,tool:catalog:read,tool:grant:minimal,tool:grant:readonly,tool:grant:messaging,token:manage_self,provider:manage_self";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SandboxOrchestratorService sandboxOrchestrator;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService, SandboxOrchestratorService sandboxOrchestrator) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.sandboxOrchestrator = sandboxOrchestrator;
    }

    public LoginResponse login(LoginRequest request) {
        UserEntity user = users.findByUsername(request.username())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        if (!"ACTIVE".equals(user.getStatus()) || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        String mergedAuthorities = mergeAuthorities(user.getAuthorities(), DEFAULT_REGISTER_AUTHORITIES);
        if (!mergedAuthorities.equals(user.getAuthorities())) {
            user.setAuthorities(mergedAuthorities);
            user.setUpdatedAt(OffsetDateTime.now());
            users.save(user);
        }
        return new LoginResponse(jwtService.issue(user.getId(), user.getUsername(), mergedAuthorities), 3600);
    }

    public LoginResponse register(RegisterRequest request) {
        String username = request.username().trim();
        users.findByUsername(username).ifPresent(user -> {
            throw new ApiException(HttpStatus.CONFLICT, "Username already exists");
        });
        OffsetDateTime now = OffsetDateTime.now();
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(blankToNull(request.displayName()));
        user.setStatus("ACTIVE");
        user.setAuthorities(DEFAULT_REGISTER_AUTHORITIES);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        UserEntity saved = users.save(user);
        sandboxOrchestrator.ensureUserNamespace(saved.getId(), saved.getUsername());
        return new LoginResponse(jwtService.issue(saved.getId(), saved.getUsername(), saved.getAuthorities()), 3600);
    }

    private String mergeAuthorities(String current, String required) {
        Set<String> values = new LinkedHashSet<>();
        for (String source : new String[] { current, required }) {
            if (source == null || source.isBlank()) {
                continue;
            }
            for (String item : source.split(",")) {
                String authority = item.trim();
                if (!authority.isBlank()) {
                    values.add(authority);
                }
            }
        }
        return String.join(",", values);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
