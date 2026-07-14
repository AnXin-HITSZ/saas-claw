package com.anxin.pyclaw.backend.secret;

import com.anxin.pyclaw.backend.audit.AuditLogService;
import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import com.anxin.pyclaw.backend.claw.ClawRepository;
import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.config.SecretEncryptionService;
import com.anxin.pyclaw.backend.sandbox.SandboxOrchestratorService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class UserSecretService {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final UserSecretRepository repository;
    private final ClawRepository claws;
    private final SecretEncryptionService encryption;
    private final SandboxOrchestratorService sandboxOrchestrator;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public UserSecretService(
            UserSecretRepository repository,
            ClawRepository claws,
            SecretEncryptionService encryption,
            SandboxOrchestratorService sandboxOrchestrator,
            ObjectMapper objectMapper,
            AuditLogService auditLogService
    ) {
        this.repository = repository;
        this.claws = claws;
        this.encryption = encryption;
        this.sandboxOrchestrator = sandboxOrchestrator;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    public List<UserSecretResponse> list(Authentication authentication) {
        String userId = actorId(authentication);
        List<UserSecretEntity> rows = isAdmin(authentication)
                ? repository.findAll()
                : repository.findByOwnerUserIdOrderByUpdatedAtDesc(userId);
        return rows.stream().map(this::toResponse).toList();
    }

    public List<UserSecretResponse> listByClaw(String clawId, Authentication authentication) {
        if (!isAdmin(authentication)) {
            requireOwnedClaw(clawId, actorId(authentication));
        }
        List<UserSecretEntity> rows = isAdmin(authentication)
                ? repository.findByClawIdAndEnabledTrue(clawId)
                : repository.findByOwnerUserIdAndClawIdOrderByUpdatedAtDesc(actorId(authentication), clawId);
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional
    public UserSecretResponse create(UserSecretRequest request, Authentication authentication) {
        String userId = actorId(authentication);
        if ("claw".equals(request.scope())) {
            String clawId = request.clawId();
            if (clawId == null || clawId.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "clawId is required when scope=claw");
            }
            requireOwnedClaw(clawId, userId);
        }

        OffsetDateTime now = OffsetDateTime.now();
        UserSecretEntity entity = new UserSecretEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setOwnerUserId(userId);
        entity.setCreatedAt(now);
        entity.setEnabled(true);
        apply(entity, request);
        entity.setUpdatedAt(now);
        UserSecretEntity saved = repository.save(entity);

        syncToKubernetes(saved);

        audit(authentication, "secret.create", saved.getId(), true, null);
        return toResponse(saved);
    }

    @Transactional
    public UserSecretResponse update(String id, UserSecretRequest request, Authentication authentication) {
        UserSecretEntity entity = requireOwned(id, authentication);

        if ("claw".equals(request.scope())) {
            String clawId = request.clawId();
            if (clawId == null || clawId.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "clawId is required when scope=claw");
            }
            requireOwnedClaw(clawId, actorId(authentication));
        }

        apply(entity, request);
        entity.setUpdatedAt(OffsetDateTime.now());
        UserSecretEntity saved = repository.save(entity);

        syncToKubernetes(saved);

        audit(authentication, "secret.update", saved.getId(), true, null);
        return toResponse(saved);
    }

    @Transactional
    public void delete(String id, Authentication authentication) {
        UserSecretEntity entity = requireOwned(id, authentication);
        if (entity.getKubernetesSecretName() != null) {
            sandboxOrchestrator.deleteClawSecretByName(entity.getOwnerUserId(), entity.getKubernetesSecretName());
        }
        repository.delete(entity);
        audit(authentication, "secret.delete", id, true, null);
    }

    @Transactional
    public UserSecretResponse sync(String id, Authentication authentication) {
        UserSecretEntity entity = requireOwned(id, authentication);
        syncToKubernetes(entity);
        audit(authentication, "secret.sync", id, true, null);
        return toResponse(entity);
    }

    private void syncToKubernetes(UserSecretEntity entity) {
        if (!sandboxOrchestrator.isEnabled()) {
            return;
        }
        Map<String, String> values = decryptValues(entity.getEncryptedValuesJson());
        if (values.isEmpty()) {
            return;
        }
        String userId = entity.getOwnerUserId();
        String clawId = entity.getClawId();
        if (clawId != null && !clawId.isBlank()) {
            sandboxOrchestrator.ensureClawSecret(userId, clawId, values);
            entity.setKubernetesSecretName(SandboxOrchestratorService.clawSecretName(clawId));
        } else {
            sandboxOrchestrator.ensureUserSecret(userId, values);
            entity.setKubernetesSecretName(SandboxOrchestratorService.userSecretName(userId));
        }
        repository.save(entity);
    }

    public Map<String, String> decryptValuesForSecret(UserSecretEntity entity) {
        return decryptValues(entity.getEncryptedValuesJson());
    }

    private UserSecretResponse toResponse(UserSecretEntity entity) {
        return new UserSecretResponse(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getScope(),
                entity.getClawId(),
                entity.getKubernetesSecretName(),
                parseMap(entity.getMaskedValuesJson()),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private void apply(UserSecretEntity entity, UserSecretRequest request) {
        entity.setName(request.name().trim());
        entity.setType(request.type().trim());
        entity.setScope(request.scope().trim());
        entity.setClawId(blankToNull(request.clawId()));

        Map<String, String> values = request.values();
        if (values != null && !values.isEmpty()) {
            entity.setEncryptedValuesJson(encryptValues(values));
            entity.setMaskedValuesJson(maskValues(values));
        }
    }

    private String encryptValues(Map<String, String> values) {
        Map<String, String> encrypted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            encrypted.put(entry.getKey(), encryption.encrypt(entry.getValue()));
        }
        try {
            return objectMapper.writeValueAsString(encrypted);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to encrypt secret values");
        }
    }

    private Map<String, String> decryptValues(String encryptedJson) {
        Map<String, String> encrypted = parseMap(encryptedJson);
        Map<String, String> decrypted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : encrypted.entrySet()) {
            decrypted.put(entry.getKey(), encryption.decrypt(entry.getValue()));
        }
        return decrypted;
    }

    private String maskValues(Map<String, String> values) {
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isEmpty()) {
                masked.put(entry.getKey(), "****");
            } else if (value.length() <= 4) {
                masked.put(entry.getKey(), "****");
            } else {
                masked.put(entry.getKey(), "****" + value.substring(value.length() - 4));
            }
        }
        try {
            return objectMapper.writeValueAsString(masked);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to mask secret values");
        }
    }

    private Map<String, String> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private UserSecretEntity requireOwned(String id, Authentication authentication) {
        UserSecretEntity entity = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Secret not found"));
        if (!isAdmin(authentication) && !Objects.equals(entity.getOwnerUserId(), actorId(authentication))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Secret not found");
        }
        return entity;
    }

    private void requireOwnedClaw(String clawId, String userId) {
        claws.findById(clawId).ifPresentOrElse(
                claw -> {
                    if (!Objects.equals(claw.getOwnerUserId(), userId)) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "Claw not found");
                    }
                },
                () -> { throw new ApiException(HttpStatus.NOT_FOUND, "Claw not found"); }
        );
    }

    private boolean isAdmin(Authentication authentication) {
        Set<String> authorities = authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return authorities.contains("user:manage");
    }

    private String actorId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    private String actorType(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.actorType();
        }
        return "UNKNOWN";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(Authentication authentication, String action, String resourceId, boolean success, String error) {
        auditLogService.record(actorType(authentication), actorId(authentication), action, "secret", resourceId, success, error);
    }
}
