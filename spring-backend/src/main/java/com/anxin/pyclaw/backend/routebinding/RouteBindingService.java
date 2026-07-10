package com.anxin.pyclaw.backend.routebinding;

import com.anxin.pyclaw.backend.agentconfig.AgentConfigEntity;
import com.anxin.pyclaw.backend.agentconfig.AgentConfigRepository;
import com.anxin.pyclaw.backend.agentconfig.AgentConfigService;
import com.anxin.pyclaw.backend.agentconfig.ToolPolicyGrantValidator;
import com.anxin.pyclaw.backend.audit.AuditLogService;
import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import com.anxin.pyclaw.backend.common.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class RouteBindingService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final Set<String> DM_SCOPES = Set.of("main", "per-peer", "per-channel-peer", "per-account-channel-peer");

    private final RouteBindingRepository bindings;
    private final AgentConfigRepository agents;
    private final AgentConfigService agentConfigService;
    private final ToolPolicyGrantValidator grantValidator;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public RouteBindingService(
            RouteBindingRepository bindings,
            AgentConfigRepository agents,
            AgentConfigService agentConfigService,
            ToolPolicyGrantValidator grantValidator,
            ObjectMapper objectMapper,
            AuditLogService auditLogService
    ) {
        this.bindings = bindings;
        this.agents = agents;
        this.agentConfigService = agentConfigService;
        this.grantValidator = grantValidator;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    public List<RouteBindingResponse> list() {
        return bindings.findAll().stream().map(this::toResponse).toList();
    }

    public List<RouteBindingResponse> runtimeList() {
        return bindings.findByEnabledTrueOrderByPriorityDescUpdatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public RouteBindingResponse create(RouteBindingRequest request, Authentication authentication) {
        AgentConfigEntity agent = requireAgent(request.agentId());
        validateRouteGrant(agent, request, authentication);
        OffsetDateTime now = OffsetDateTime.now();
        RouteBindingEntity entity = new RouteBindingEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setCreatedAt(now);
        apply(entity, request);
        entity.setUpdatedAt(now);
        RouteBindingEntity saved = bindings.save(entity);
        audit(authentication, "route_binding.create", saved.getId(), true, null);
        return toResponse(saved);
    }

    @Transactional
    public RouteBindingResponse update(String id, RouteBindingRequest request, Authentication authentication) {
        RouteBindingEntity entity = bindings.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Route binding not found"));
        AgentConfigEntity agent = requireAgent(request.agentId());
        validateRouteGrant(agent, request, authentication);
        apply(entity, request);
        entity.setUpdatedAt(OffsetDateTime.now());
        RouteBindingEntity saved = bindings.save(entity);
        audit(authentication, "route_binding.update", saved.getId(), true, null);
        return toResponse(saved);
    }

    @Transactional
    public void delete(String id, Authentication authentication) {
        bindings.deleteById(id);
        audit(authentication, "route_binding.delete", id, true, null);
    }

    public RouteBindingResponse toResponse(RouteBindingEntity entity) {
        AgentConfigEntity agent = agents.findById(entity.getAgentId()).orElse(null);
        return new RouteBindingResponse(
                entity.getId(),
                entity.isEnabled(),
                entity.getPriority(),
                entity.getAgentId(),
                agent == null ? null : agent.getAgentKey(),
                agent == null ? null : agent.getName(),
                entity.getChannel(),
                entity.getAccountId(),
                entity.getPeerKind(),
                entity.getPeerId(),
                entity.getParentPeerKind(),
                entity.getParentPeerId(),
                entity.getGuildId(),
                entity.getTeamId(),
                readList(entity.getRolesJson()),
                readList(entity.getSenderIdsJson()),
                readList(entity.getMentionAliasesJson()),
                readList(entity.getCommandPrefixesJson()),
                entity.getDmScope(),
                entity.getComment(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private void apply(RouteBindingEntity entity, RouteBindingRequest request) {
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setPriority(request.priority() == null ? 0 : request.priority());
        entity.setAgentId(request.agentId());
        entity.setChannel(normalize(request.channel()));
        entity.setAccountId(blankToNull(request.accountId()));
        entity.setPeerKind(normalize(request.peerKind()));
        entity.setPeerId(blankToNull(request.peerId()));
        entity.setParentPeerKind(normalize(request.parentPeerKind()));
        entity.setParentPeerId(blankToNull(request.parentPeerId()));
        entity.setGuildId(blankToNull(request.guildId()));
        entity.setTeamId(blankToNull(request.teamId()));
        entity.setRolesJson(writeList(request.roles()));
        entity.setSenderIdsJson(writeList(request.senderIds()));
        entity.setMentionAliasesJson(writeList(request.mentionAliases()));
        entity.setCommandPrefixesJson(writeList(request.commandPrefixes()));
        entity.setDmScope(normalizeDmScope(request.dmScope()));
        entity.setComment(blankToNull(request.comment()));
    }

    private void validateRouteGrant(AgentConfigEntity agent, RouteBindingRequest request, Authentication authentication) {
        grantValidator.requireCanRouteTo(agentConfigService.requirePolicy(agent.getId()), authentication);
        Set<String> authorities = authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        boolean channelDefault = blankToNull(request.channel()) != null
                && blankToNull(request.peerId()) == null
                && blankToNull(request.guildId()) == null
                && blankToNull(request.teamId()) == null
                && (request.senderIds() == null || request.senderIds().isEmpty())
                && (request.mentionAliases() == null || request.mentionAliases().isEmpty())
                && (request.commandPrefixes() == null || request.commandPrefixes().isEmpty());
        if (channelDefault && !authorities.contains("channel:manage")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Channel default route requires channel:manage");
        }
    }

    private AgentConfigEntity requireAgent(String id) {
        return agents.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent not found"));
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception exc) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid route binding JSON");
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid route binding list");
        }
    }

    private String normalizeDmScope(String value) {
        String scope = normalize(value);
        if (scope == null) {
            return "per-account-channel-peer";
        }
        if (!DM_SCOPES.contains(scope)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported dmScope: " + value);
        }
        return scope;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT).replace("_", "-");
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
        auditLogService.record(actorType(authentication), actorId(authentication), action, "route_binding", resourceId, success, error);
    }
}
