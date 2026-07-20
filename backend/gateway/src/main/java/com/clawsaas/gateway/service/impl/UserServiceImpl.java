package com.clawsaas.gateway.service.impl;

import com.clawsaas.gateway.dto.CreateUserRequest;
import com.clawsaas.gateway.entity.UserEntity;
import com.clawsaas.gateway.exception.ApiException;
import com.clawsaas.gateway.repository.UserRepository;
import com.clawsaas.gateway.service.SandboxOrchestratorService;
import com.clawsaas.gateway.service.UserService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {
    private static final String DEFAULT_USER_AUTHORITIES = "claw:read,claw:create,claw:update,claw:delete,agent:run,agent:read,agent:create,agent:update,tool:catalog:read,tool:grant:minimal,tool:grant:readonly,tool:grant:messaging,token:manage_self,provider:manage_self";

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SandboxOrchestratorService sandboxOrchestrator;

    public UserServiceImpl(UserRepository repository, PasswordEncoder passwordEncoder, SandboxOrchestratorService sandboxOrchestrator) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.sandboxOrchestrator = sandboxOrchestrator;
    }

    @Override
    public List<UserEntity> list() {
        return repository.findAll();
    }

    @Override
    public UserEntity create(CreateUserRequest request) {
        repository.findByUsername(request.username()).ifPresent(user -> {
            throw new ApiException(HttpStatus.CONFLICT, "Username already exists");
        });
        OffsetDateTime now = OffsetDateTime.now();
        UserEntity entity = new UserEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUsername(request.username());
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity.setDisplayName(request.displayName());
        entity.setStatus("ACTIVE");
        entity.setAuthorities(request.authorities() == null || request.authorities().isBlank() ? DEFAULT_USER_AUTHORITIES : request.authorities());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        UserEntity saved = repository.save(entity);
        sandboxOrchestrator.ensureUserNamespace(saved.getId(), saved.getUsername());
        return saved;
    }

    @Override
    public UserEntity disable(String id) {
        UserEntity entity = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        entity.setStatus("DISABLED");
        entity.setUpdatedAt(OffsetDateTime.now());
        UserEntity saved = repository.save(entity);
        sandboxOrchestrator.scaleUserDeployments(saved.getId(), 0);
        return saved;
    }
}
