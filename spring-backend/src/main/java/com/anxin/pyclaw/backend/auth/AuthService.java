package com.anxin.pyclaw.backend.auth;

import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.user.UserEntity;
import com.anxin.pyclaw.backend.user.UserRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private static final String DEFAULT_REGISTER_AUTHORITIES = "claw:read,claw:create,claw:update,claw:delete,agent:run,agent:read,token:manage_self";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        UserEntity user = users.findByUsername(request.username())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        if (!"ACTIVE".equals(user.getStatus()) || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        return new LoginResponse(jwtService.issue(user.getId(), user.getUsername(), user.getAuthorities()), 3600);
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
        return new LoginResponse(jwtService.issue(saved.getId(), saved.getUsername(), saved.getAuthorities()), 3600);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
