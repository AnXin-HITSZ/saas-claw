package com.anxin.pyclaw.backend.agentconfig;

import com.anxin.pyclaw.backend.audit.AuditLogService;
import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import com.anxin.pyclaw.backend.common.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AgentConfigService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final AgentConfigRepository agents;
    private final AgentToolPolicyRepository policies;
    private final ObjectMapper objectMapper;
    private final ToolPolicyGrantValidator grantValidator;
    private final AuditLogService auditLogService;

    public AgentConfigService(
            AgentConfigRepository agents,
            AgentToolPolicyRepository policies,
            ObjectMapper objectMapper,
            ToolPolicyGrantValidator grantValidator,
            AuditLogService auditLogService
    ) {
        this.agents = agents;
        this.policies = policies;
        this.objectMapper = objectMapper;
        this.grantValidator = grantValidator;
        this.auditLogService = auditLogService;
    }

    public List<AgentConfigResponse> list() {
        return agents.findAll().stream().map(this::toResponse).toList();
    }

    public AgentConfigResponse get(String id) {
        return toResponse(requireAgent(id));
    }

    @Transactional
    public AgentConfigResponse create(AgentConfigRequest request, Authentication authentication) {
        grantValidator.validate(request.toolPolicy(), authentication);
        if (agents.findByAgentKey(normalizeAgentKey(request.agentKey())).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Agent key already exists");
        }
        OffsetDateTime now = OffsetDateTime.now();
        AgentConfigEntity entity = new AgentConfigEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(authentication));
        apply(entity, request);
        entity.setUpdatedAt(now);
        AgentConfigEntity saved = agents.save(entity);
        policies.save(newPolicy(saved.getId(), request.toolPolicy(), now));
        audit(authentication, "agent.create", saved.getId(), true, null);
        return toResponse(saved);
    }

    @Transactional
    public AgentConfigResponse update(String id, AgentConfigRequest request, Authentication authentication) {
        grantValidator.validate(request.toolPolicy(), authentication);
        AgentConfigEntity entity = requireAgent(id);
        String nextKey = normalizeAgentKey(request.agentKey());
        agents.findByAgentKey(nextKey).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new ApiException(HttpStatus.CONFLICT, "Agent key already exists");
            }
        });
        apply(entity, request);
        entity.setUpdatedAt(OffsetDateTime.now());
        AgentConfigEntity saved = agents.save(entity);
        AgentToolPolicyEntity policy = policies.findByAgentId(id).orElseGet(() -> newPolicy(id, null, OffsetDateTime.now()));
        applyPolicy(policy, request.toolPolicy(), policy.getCreatedAt());
        policies.save(policy);
        audit(authentication, "agent.update", saved.getId(), true, null);
        return toResponse(saved);
    }

    @Transactional
    public void delete(String id, Authentication authentication) {
        requireAgent(id);
        policies.deleteByAgentId(id);
        agents.deleteById(id);
        audit(authentication, "agent.delete", id, true, null);
    }

    public AgentConfigEntity requireEnabledByKey(String agentKey) {
        return agents.findByAgentKeyAndEnabledTrue(normalizeAgentKey(agentKey))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent runtime config not found"));
    }

    public AgentToolPolicyEntity requirePolicy(String agentId) {
        return policies.findByAgentId(agentId)
                .orElseGet(() -> newPolicy(agentId, null, OffsetDateTime.now()));
    }

    public AgentConfigResponse toResponse(AgentConfigEntity entity) {
        return new AgentConfigResponse(
                entity.getId(),
                entity.getAgentKey(),
                entity.getName(),
                entity.getDescription(),
                entity.isEnabled(),
                entity.getProviderId(),
                entity.getProvider(),
                entity.getModel(),
                entity.getSystemPrompt(),
                entity.getWorkspaceDir(),
                entity.getRuntimeType(),
                entity.getCreatedBy(),
                toPolicyResponse(requirePolicy(entity.getId())),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public AgentToolPolicyResponse toPolicyResponse(AgentToolPolicyEntity entity) {
        return new AgentToolPolicyResponse(
                entity.getId(),
                entity.getProfile(),
                readListOrNull(entity.getToolsAllowJson()),
                readList(entity.getToolsDenyJson()),
                readList(entity.getToolsAlsoAllowJson()),
                entity.isWorkspaceOnly(),
                entity.isReadonly(),
                entity.getShellApproval(),
                entity.isWebAccess(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public List<String> readList(String json) {
        List<String> values = readListOrNull(json);
        return values == null ? List.of() : values;
    }

    public List<String> readListOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception exc) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid stored tool policy JSON");
        }
    }

    private AgentConfigEntity requireAgent(String id) {
        return agents.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent not found"));
    }

    private void apply(AgentConfigEntity entity, AgentConfigRequest request) {
        entity.setAgentKey(normalizeAgentKey(request.agentKey()));
        entity.setName(request.name().trim());
        entity.setDescription(blankToNull(request.description()));
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setProviderId(blankToNull(request.providerId()));
        entity.setProvider(blankToNull(request.provider()));
        entity.setModel(blankToNull(request.model()));
        entity.setSystemPrompt(blankToNull(request.systemPrompt()));
        entity.setWorkspaceDir(blankToNull(request.workspaceDir()));
        entity.setRuntimeType(blankToNull(request.runtimeType()) == null ? "agent_session" : request.runtimeType().trim());
    }

    private AgentToolPolicyEntity newPolicy(String agentId, AgentToolPolicyRequest request, OffsetDateTime now) {
        AgentToolPolicyEntity entity = new AgentToolPolicyEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setAgentId(agentId);
        entity.setCreatedAt(now);
        applyPolicy(entity, request, now);
        return entity;
    }

    private void applyPolicy(AgentToolPolicyEntity entity, AgentToolPolicyRequest request, OffsetDateTime now) {
        AgentToolPolicyRequest policy = request == null
                ? new AgentToolPolicyRequest("messaging", null, List.of(), List.of(), true, false, "deny", false)
                : request;
        entity.setProfile(grantValidator.normalizeProfile(policy.profile()));
        entity.setToolsAllowJson(writeNullableList(policy.toolsAllow()));
        entity.setToolsDenyJson(writeList(policy.toolsDeny()));
        entity.setToolsAlsoAllowJson(writeList(policy.toolsAlsoAllow()));
        entity.setWorkspaceOnly(policy.workspaceOnly() == null || policy.workspaceOnly());
        entity.setReadonly(Boolean.TRUE.equals(policy.readonly()) || "readonly".equals(entity.getProfile()));
        entity.setShellApproval(grantValidator.normalizeShellApproval(policy.shellApproval()));
        entity.setWebAccess(Boolean.TRUE.equals(policy.webAccess()));
        entity.setUpdatedAt(OffsetDateTime.now());
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
    }

    private String writeList(List<String> values) {
        return writeNullableList(values == null ? List.of() : values);
    }

    private String writeNullableList(List<String> values) {
        if (values == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().replace("-", "_"))
                    .distinct()
                    .toList());
        } catch (Exception exc) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid tool policy list");
        }
    }

    private String normalizeAgentKey(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentKey is required");
        }
        String key = value.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        if (key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentKey is invalid");
        }
        return key;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private void audit(Authentication authentication, String action, String resourceId, boolean success, String error) {
        auditLogService.record(actorType(authentication), actorId(authentication), action, "agent", resourceId, success, error);
    }
}
