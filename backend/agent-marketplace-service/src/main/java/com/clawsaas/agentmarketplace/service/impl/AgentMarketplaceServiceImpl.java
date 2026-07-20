package com.clawsaas.agentmarketplace.service.impl;

import com.clawsaas.agentmarketplace.client.AgentConfigClient;
import com.clawsaas.agentmarketplace.client.AgentConfigClient.AgentConfigDTO;
import com.clawsaas.agentmarketplace.client.AgentToolPolicyClient;
import com.clawsaas.agentmarketplace.client.AgentToolPolicyClient.AgentToolPolicyDTO;
import com.clawsaas.agentmarketplace.client.AuditLogClient;
import com.clawsaas.agentmarketplace.domain.AuthenticatedPrincipal;
import com.clawsaas.agentmarketplace.dto.AgentPackageResponse;
import com.clawsaas.agentmarketplace.dto.AgentPackageVersionResponse;
import com.clawsaas.agentmarketplace.dto.AgentPublishRequest;
import com.clawsaas.agentmarketplace.entity.AgentPackageEntity;
import com.clawsaas.agentmarketplace.entity.AgentPackageVersionEntity;
import com.clawsaas.agentmarketplace.exception.ApiException;
import com.clawsaas.agentmarketplace.repository.AgentPackageRepository;
import com.clawsaas.agentmarketplace.repository.AgentPackageVersionRepository;
import com.clawsaas.agentmarketplace.service.AgentMarketplaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
public class AgentMarketplaceServiceImpl implements AgentMarketplaceService {

    private static final String VISIBILITY_PRIVATE = "private";
    private static final String VISIBILITY_UNLISTED = "unlisted";
    private static final String VISIBILITY_PUBLIC = "public";
    private static final Set<String> VISIBILITIES = Set.of(VISIBILITY_PRIVATE, VISIBILITY_UNLISTED, VISIBILITY_PUBLIC);
    private static final String VERSION_STATUS_PUBLISHED = "published";

    private final AgentPackageRepository packages;
    private final AgentPackageVersionRepository versions;
    private final AgentConfigClient agentConfigClient;
    private final AgentToolPolicyClient agentToolPolicyClient;
    private final ObjectMapper objectMapper;
    private final AuditLogClient auditLogClient;

    public AgentMarketplaceServiceImpl(
            AgentPackageRepository packages,
            AgentPackageVersionRepository versions,
            AgentConfigClient agentConfigClient,
            AgentToolPolicyClient agentToolPolicyClient,
            ObjectMapper objectMapper,
            AuditLogClient auditLogClient
    ) {
        this.packages = packages;
        this.versions = versions;
        this.agentConfigClient = agentConfigClient;
        this.agentToolPolicyClient = agentToolPolicyClient;
        this.objectMapper = objectMapper;
        this.auditLogClient = auditLogClient;
    }

    @Transactional
    public AgentPackageVersionResponse publish(String agentId, AgentPublishRequest request, Authentication authentication) {
        String actorId = actorId(authentication);
        boolean admin = isAdmin(authentication);

        AgentConfigDTO agent = agentConfigClient.findById(agentId);
        if (!admin && !Objects.equals(agent.createdBy(), actorId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent not found");
        }

        if (agent.name() == null || agent.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Agent name is required to publish");
        }
        if (agent.systemPrompt() == null || agent.systemPrompt().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Agent systemPrompt is required to publish");
        }
        AgentToolPolicyDTO policy = agentToolPolicyClient.findByAgentId(agentId);

        String packageKey = normalizePackageKey(request.packageKey());
        String version = normalizeVersion(request.version());
        String visibility = normalizeVisibility(request.visibility());

        OffsetDateTime now = OffsetDateTime.now();
        AgentPackageEntity pkg = packages.findByOwnerUserIdAndPackageKey(actorId, packageKey)
                .orElseGet(() -> newPackage(actorId, packageKey, now));
        pkg.setName(agent.name());
        pkg.setSummary(blankToNull(request.summary()));
        pkg.setDescription(agent.description());
        pkg.setVisibility(visibility);
        pkg.setUpdatedAt(now);

        if (versions.findByPackageIdAndVersion(pkg.getId(), version).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Package version already exists");
        }

        AgentPackageVersionEntity ver = new AgentPackageVersionEntity();
        ver.setId(UUID.randomUUID().toString());
        ver.setPackageId(pkg.getId());
        ver.setVersion(version);
        ver.setStatus(VERSION_STATUS_PUBLISHED);
        ver.setDefaultProfile(policy.profile());
        ver.setRequiredProfile(policy.profile());
        ver.setChangelog(blankToNull(request.changelog()));
        ver.setSystemPromptSnapshot(agent.systemPrompt());
        ver.setPersonaFilesJson(null);
        ver.setSkillFilesJson(null);
        ver.setCapabilitiesJson(null);
        ver.setInputContractJson(null);
        ver.setOutputContractJson(null);
        ver.setManifestJson(buildManifest(agent, pkg, version, policy.profile(), visibility, request));
        ver.setCreatedAt(now);

        AgentPackageEntity savedPkg = packages.save(pkg);
        ver.setPackageId(savedPkg.getId());
        AgentPackageVersionEntity savedVer = versions.save(ver);
        savedPkg.setLatestVersionId(savedVer.getId());
        packages.save(savedPkg);

        audit(authentication, "agent_package.publish", savedVer.getId(), true, null);
        return toVersionResponse(savedVer);
    }

    public List<AgentPackageResponse> list(Authentication authentication) {
        String actorId = actorId(authentication);
        boolean admin = isAdmin(authentication);
        Map<String, AgentPackageEntity> byId = new LinkedHashMap<>();
        for (AgentPackageEntity pkg : packages.findByVisibilityOrderByUpdatedAtDesc(VISIBILITY_PUBLIC)) {
            byId.put(pkg.getId(), pkg);
        }
        if (actorId != null) {
            for (AgentPackageEntity pkg : packages.findByOwnerUserIdOrderByUpdatedAtDesc(actorId)) {
                byId.put(pkg.getId(), pkg);
            }
        }
        if (admin) {
            for (AgentPackageEntity pkg : packages.findAll()) {
                byId.put(pkg.getId(), pkg);
            }
        }
        List<AgentPackageEntity> rows = new ArrayList<>(byId.values());
        rows.sort((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()));
        return rows.stream().map(this::toPackageResponse).toList();
    }

    public AgentPackageResponse get(String packageId, Authentication authentication) {
        return toPackageResponse(requireVisible(packageId, authentication));
    }

    public List<AgentPackageVersionResponse> listVersions(String packageId, Authentication authentication) {
        AgentPackageEntity pkg = requireVisible(packageId, authentication);
        return versions.findByPackageIdOrderByCreatedAtDesc(pkg.getId()).stream()
                .map(this::toVersionResponse)
                .toList();
    }

    private AgentPackageEntity requireVisible(String packageId, Authentication authentication) {
        AgentPackageEntity pkg = packages.findById(packageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent package not found"));
        String actorId = actorId(authentication);
        boolean admin = isAdmin(authentication);
        if (admin || VISIBILITY_PUBLIC.equals(pkg.getVisibility()) || Objects.equals(pkg.getOwnerUserId(), actorId)) {
            return pkg;
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "Agent package not found");
    }

    private AgentPackageEntity newPackage(String ownerUserId, String packageKey, OffsetDateTime now) {
        AgentPackageEntity pkg = new AgentPackageEntity();
        pkg.setId(UUID.randomUUID().toString());
        pkg.setOwnerUserId(ownerUserId);
        pkg.setPackageKey(packageKey);
        pkg.setInstallCount(0L);
        pkg.setCreatedAt(now);
        return pkg;
    }

    private String buildManifest(AgentConfigDTO agent, AgentPackageEntity pkg, String version,
                                 String defaultProfile, String visibility, AgentPublishRequest request) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("agent_key", agent.agentKey());
        manifest.put("name", agent.name());
        manifest.put("summary", blankToNull(request.summary()));
        manifest.put("description", agent.description());
        manifest.put("version", version);
        manifest.put("publisher_user_id", pkg.getOwnerUserId());
        manifest.put("default_profile", defaultProfile);
        manifest.put("required_profile", defaultProfile);
        manifest.put("visibility", visibility);
        manifest.put("provider", agent.provider());
        manifest.put("model", agent.model());
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (Exception exc) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize manifest");
        }
    }

    private AgentPackageResponse toPackageResponse(AgentPackageEntity pkg) {
        return new AgentPackageResponse(
                pkg.getId(),
                pkg.getPackageKey(),
                pkg.getOwnerUserId(),
                pkg.getName(),
                pkg.getSummary(),
                pkg.getDescription(),
                pkg.getVisibility(),
                pkg.getLatestVersionId(),
                pkg.getInstallCount(),
                pkg.getCreatedAt(),
                pkg.getUpdatedAt()
        );
    }

    private AgentPackageVersionResponse toVersionResponse(AgentPackageVersionEntity ver) {
        return new AgentPackageVersionResponse(
                ver.getId(),
                ver.getPackageId(),
                ver.getVersion(),
                ver.getStatus(),
                ver.getDefaultProfile(),
                ver.getRequiredProfile(),
                ver.getChangelog(),
                ver.getManifestJson(),
                ver.getCreatedAt()
        );
    }

    private String normalizePackageKey(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "packageKey is required");
        }
        String key = value.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        if (key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "packageKey is invalid");
        }
        return key;
    }

    private String normalizeVersion(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "version is required");
        }
        return value.trim();
    }

    private String normalizeVisibility(String value) {
        if (value == null || value.isBlank()) {
            return VISIBILITY_PRIVATE;
        }
        String v = value.trim().toLowerCase();
        if (!VISIBILITIES.contains(v)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "visibility must be one of private, unlisted, public");
        }
        return v;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private void audit(Authentication authentication, String action, String resourceId, boolean success, String error) {
        auditLogClient.record(actorType(authentication), actorId(authentication), action, "agent_package", resourceId, success, error);
    }
}
