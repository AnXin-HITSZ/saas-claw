package com.clawsaas.claw.service.impl;

import com.clawsaas.claw.client.AuditLogClient;
import com.clawsaas.claw.client.SandboxClient;
import com.clawsaas.claw.domain.AgentConfigEntity;
import com.clawsaas.claw.domain.AuthenticatedPrincipal;
import com.clawsaas.claw.domain.ClawAgentEntity;
import com.clawsaas.claw.domain.ClawEntity;
import com.clawsaas.claw.domain.RouteBindingEntity;
import com.clawsaas.claw.dto.ClawRequest;
import com.clawsaas.claw.dto.ClawResponse;
import com.clawsaas.claw.dto.ClawRoleRequest;
import com.clawsaas.claw.dto.ClawRoleResponse;
import com.clawsaas.claw.exception.ApiException;
import com.clawsaas.claw.repository.AgentConfigRepository;
import com.clawsaas.claw.repository.ClawAgentRepository;
import com.clawsaas.claw.repository.ClawRepository;
import com.clawsaas.claw.repository.RouteBindingRepository;
import com.clawsaas.claw.service.ClawService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClawServiceImpl implements ClawService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ClawRepository claws;
    private final ClawAgentRepository clawAgents;
    private final AgentConfigRepository agents;
    private final RouteBindingRepository routeBindings;
    private final ObjectMapper objectMapper;
    private final AuditLogClient auditLogClient;
    private final SandboxClient sandboxClient;

    public ClawServiceImpl(
            ClawRepository claws,
            ClawAgentRepository clawAgents,
            AgentConfigRepository agents,
            RouteBindingRepository routeBindings,
            ObjectMapper objectMapper,
            AuditLogClient auditLogClient,
            SandboxClient sandboxClient
    ) {
        this.claws = claws;
        this.clawAgents = clawAgents;
        this.agents = agents;
        this.routeBindings = routeBindings;
        this.objectMapper = objectMapper;
        this.auditLogClient = auditLogClient;
        this.sandboxClient = sandboxClient;
    }

    @Override
    public List<ClawResponse> list(Authentication authentication) {
        String ownerUserId = actorId(authentication);
        List<ClawEntity> rows = isAdmin(authentication) ? claws.findAll() : claws.findByOwnerUserIdOrderByUpdatedAtDesc(ownerUserId);
        return rows.stream().map(this::toResponse).toList();
    }

    @Override
    public ClawResponse get(String id, Authentication authentication) {
        return toResponse(requireOwned(id, authentication));
    }

    @Override
    @Transactional
    public ClawResponse create(ClawRequest request, Authentication authentication) {
        OffsetDateTime now = OffsetDateTime.now();
        ClawEntity entity = new ClawEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setOwnerUserId(actorId(authentication));
        entity.setCreatedAt(now);
        apply(entity, request);
        entity.setUpdatedAt(now);
        ClawEntity saved = claws.save(entity);
        replaceRoles(saved, request.roles(), now);
        try {
            sandboxClient.ensureClawSandbox(saved.getOwnerUserId(), actorName(authentication), saved.getId(), saved.getName());
        } catch (Exception ignored) {
            // Sandbox provisioning is non-critical during CRUD
        }
        audit(authentication, "claw.create", saved.getId(), true, null);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public ClawResponse update(String id, ClawRequest request, Authentication authentication) {
        ClawEntity entity = requireOwned(id, authentication);
        apply(entity, request);
        entity.setUpdatedAt(OffsetDateTime.now());
        ClawEntity saved = claws.save(entity);
        replaceRoles(saved, request.roles(), OffsetDateTime.now());
        try {
            sandboxClient.ensureClawSandbox(saved.getOwnerUserId(), actorName(authentication), saved.getId(), saved.getName());
        } catch (Exception ignored) {
            // Sandbox provisioning is non-critical during CRUD
        }
        applyClawStatusSandbox(saved);
        audit(authentication, "claw.update", saved.getId(), true, null);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public ClawResponse syncRoutes(String id, Authentication authentication) {
        ClawEntity entity = requireOwned(id, authentication);
        List<ClawAgentEntity> roles = clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(id);
        roles.forEach(role -> syncRoleRoute(entity, role));
        audit(authentication, "claw.sync_routes", entity.getId(), true, null);
        return toResponse(entity);
    }

    @Override
    @Transactional
    public void delete(String id, Authentication authentication) {
        ClawEntity entity = requireOwned(id, authentication);
        for (ClawAgentEntity role : clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(id)) {
            deleteRoleRoute(role);
        }
        clawAgents.deleteByClawId(id);
        try {
            sandboxClient.deleteClawSandbox(entity.getOwnerUserId(), id);
        } catch (Exception ignored) {
            // Non-critical
        }
        claws.delete(entity);
        audit(authentication, "claw.delete", id, true, null);
    }

    @Override
    public ClawResponse toResponse(ClawEntity entity) {
        return new ClawResponse(
                entity.getId(),
                entity.getOwnerUserId(),
                entity.getName(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getDefaultAgentId(),
                entity.isFeishuEnabled(),
                entity.getFeishuAccountId(),
                entity.getFeishuPeerKind(),
                entity.getFeishuPeerId(),
                entity.getFeishuComment(),
                clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(entity.getId()).stream().map(this::toRoleResponse).toList(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    @Override
    public ClawRoleResponse toRoleResponsePublic(ClawAgentEntity entity) {
        return toRoleResponse(entity);
    }

    private ClawRoleResponse toRoleResponse(ClawAgentEntity entity) {
        AgentConfigEntity agent = agents.findById(entity.getAgentId()).orElse(null);
        return new ClawRoleResponse(
                entity.getId(),
                entity.getClawId(),
                entity.getAgentId(),
                agent == null ? null : agent.getAgentKey(),
                agent == null ? null : agent.getName(),
                entity.getRoleKey(),
                entity.getDisplayName(),
                readList(entity.getMentionAliasesJson()),
                readList(entity.getCommandPrefixesJson()),
                entity.isDefaultRole(),
                entity.isEnabled(),
                entity.getSortOrder(),
                entity.getRouteBindingId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getSourceType(),
                entity.getSourceAgentId(),
                entity.getPackageId(),
                entity.getPackageVersionId(),
                entity.getLocalSystemPromptOverride(),
                entity.getLocalProfile(),
                entity.getInstalledBy(),
                entity.getInstalledAt()
        );
    }

    private void replaceRoles(ClawEntity claw, List<ClawRoleRequest> requests, OffsetDateTime now) {
        for (ClawAgentEntity role : clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(claw.getId())) {
            deleteRoleRoute(role);
        }
        clawAgents.deleteByClawId(claw.getId());
        List<ClawRoleRequest> values = requests == null ? List.of() : requests;
        boolean hasDefault = values.stream().anyMatch(role -> Boolean.TRUE.equals(role.defaultRole()));
        boolean defaultAssigned = false;
        for (int index = 0; index < values.size(); index++) {
            ClawAgentEntity role = new ClawAgentEntity();
            role.setId(UUID.randomUUID().toString());
            role.setClawId(claw.getId());
            role.setCreatedAt(now);
            applyRole(role, values.get(index), index, !hasDefault && index == 0);
            if (role.isDefaultRole()) {
                if (defaultAssigned) {
                    role.setDefaultRole(false);
                } else {
                    defaultAssigned = true;
                }
            }
            role.setUpdatedAt(now);
            ClawAgentEntity saved = clawAgents.save(role);
            syncRoleRoute(claw, saved);
        }
    }

    private void apply(ClawEntity entity, ClawRequest request) {
        entity.setName(request.name().trim());
        entity.setDescription(blankToNull(request.description()));
        entity.setStatus(blankToNull(request.status()) == null ? "active" : normalize(request.status()));
        String defaultAgentId = blankToNull(request.defaultAgentId());
        if (defaultAgentId != null) {
            requireAgent(defaultAgentId);
        }
        entity.setDefaultAgentId(defaultAgentId);
        entity.setFeishuEnabled(Boolean.TRUE.equals(request.feishuEnabled()));
        entity.setFeishuAccountId(blankToNull(request.feishuAccountId()));
        entity.setFeishuPeerKind(normalize(blankToNull(request.feishuPeerKind())) == null ? "group" : normalize(request.feishuPeerKind()));
        entity.setFeishuPeerId(blankToNull(request.feishuPeerId()));
        entity.setFeishuComment(blankToNull(request.feishuComment()));
        ensureUniqueFeishuBinding(entity);
    }

    private void applyRole(ClawAgentEntity entity, ClawRoleRequest request, int index, boolean defaultWhenMissing) {
        requireAgent(request.agentId());
        entity.setAgentId(request.agentId());
        entity.setRoleKey(normalizeRoleKey(request.roleKey()));
        entity.setDisplayName(request.displayName().trim());
        entity.setMentionAliasesJson(writeList(request.mentionAliases()));
        entity.setCommandPrefixesJson(writeList(request.commandPrefixes()));
        entity.setDefaultRole(Boolean.TRUE.equals(request.defaultRole()) || defaultWhenMissing);
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setSortOrder(request.sortOrder() == null ? index : request.sortOrder());
    }

    private void syncRoleRoute(ClawEntity claw, ClawAgentEntity role) {
        if (!claw.isFeishuEnabled() || blankToNull(claw.getFeishuPeerId()) == null || !role.isEnabled()) {
            deleteRoleRoute(role);
            clawAgents.save(role);
            return;
        }
        RouteBindingEntity route = role.getRouteBindingId() == null
                ? new RouteBindingEntity()
                : routeBindings.findById(role.getRouteBindingId()).orElseGet(RouteBindingEntity::new);
        OffsetDateTime now = OffsetDateTime.now();
        if (route.getId() == null) {
            route.setId(UUID.randomUUID().toString());
            route.setCreatedAt(now);
        }
        route.setEnabled(true);
        route.setPriority(role.isDefaultRole() ? 0 : 100 + Math.max(0, 100 - role.getSortOrder()));
        route.setClawId(claw.getId());
        route.setAgentId(role.getAgentId());
        route.setChannel("feishu");
        route.setAccountId(blankToNull(claw.getFeishuAccountId()));
        route.setPeerKind(blankToNull(claw.getFeishuPeerKind()) == null ? "group" : claw.getFeishuPeerKind());
        route.setPeerId(claw.getFeishuPeerId());
        route.setParentPeerKind(null);
        route.setParentPeerId(null);
        route.setGuildId(null);
        route.setTeamId(null);
        route.setRolesJson(writeList(List.of()));
        route.setSenderIdsJson(writeList(List.of()));
        route.setMentionAliasesJson(role.isDefaultRole() ? writeList(List.of()) : role.getMentionAliasesJson());
        route.setCommandPrefixesJson(role.isDefaultRole() ? writeList(List.of()) : role.getCommandPrefixesJson());
        route.setDmScope("per-account-channel-peer");
        route.setComment("Managed by Claw " + claw.getName() + " / " + role.getDisplayName());
        route.setUpdatedAt(now);
        RouteBindingEntity saved = routeBindings.save(route);
        role.setRouteBindingId(saved.getId());
        role.setUpdatedAt(now);
        clawAgents.save(role);
    }

    private void deleteRoleRoute(ClawAgentEntity role) {
        if (role.getRouteBindingId() != null) {
            routeBindings.findById(role.getRouteBindingId()).ifPresent(routeBindings::delete);
            role.setRouteBindingId(null);
        }
    }

    private ClawEntity requireOwned(String id, Authentication authentication) {
        ClawEntity claw = claws.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claw not found"));
        if (!isAdmin(authentication) && !claw.getOwnerUserId().equals(actorId(authentication))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Claw not found");
        }
        return claw;
    }

    private AgentConfigEntity requireAgent(String id) {
        return agents.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent not found"));
    }

    private void ensureUniqueFeishuBinding(ClawEntity entity) {
        if (!entity.isFeishuEnabled() || blankToNull(entity.getFeishuPeerId()) == null) {
            return;
        }
        boolean exists = claws.existsFeishuBinding(
                blankToNull(entity.getFeishuAccountId()),
                blankToNull(entity.getFeishuPeerKind()) == null ? "group" : entity.getFeishuPeerKind(),
                entity.getFeishuPeerId(),
                entity.getId()
        );
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "Feishu peer already bound to another Claw");
        }
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception exc) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid Claw role JSON");
        }
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList());
        } catch (Exception exc) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid Claw role list");
        }
    }

    private String normalizeRoleKey(String value) {
        String key = normalize(value);
        if (key == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "roleKey is required");
        }
        return key.replaceAll("[^a-z0-9_-]", "-");
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT).replace("_", "-");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isAdmin(Authentication authentication) {
        Set<String> authorities = authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        return authorities.contains("user:manage");
    }

    private String actorId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    private String actorName(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.getUsername();
        }
        return actorId(authentication);
    }

    private String actorType(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.actorType();
        }
        return "UNKNOWN";
    }

    private void applyClawStatusSandbox(ClawEntity claw) {
        try {
            if (!sandboxClient.isEnabled()) {
                return;
            }
            if ("active".equals(claw.getStatus())) {
                sandboxClient.scaleClawDeployment(claw.getOwnerUserId(), claw.getId(), 1);
            } else if ("inactive".equals(claw.getStatus())) {
                sandboxClient.scaleClawDeployment(claw.getOwnerUserId(), claw.getId(), 0);
            }
        } catch (Exception ignored) {
            // Non-critical
        }
    }

    private void audit(Authentication authentication, String action, String resourceId, boolean success, String error) {
        auditLogClient.record(actorType(authentication), actorId(authentication), action, "claw", resourceId, success, error);
    }
}
