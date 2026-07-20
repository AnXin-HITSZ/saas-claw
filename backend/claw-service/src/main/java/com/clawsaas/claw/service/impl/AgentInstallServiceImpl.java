package com.clawsaas.claw.service.impl;

import com.clawsaas.claw.client.AgentPackageClient;
import com.clawsaas.claw.client.AuditLogClient;
import com.clawsaas.claw.domain.AgentInstallApprovalEntity;
import com.clawsaas.claw.domain.AgentInstallApprovalStatus;
import com.clawsaas.claw.domain.AuthenticatedPrincipal;
import com.clawsaas.claw.domain.ClawAgentEntity;
import com.clawsaas.claw.domain.ClawEntity;
import com.clawsaas.claw.dto.AgentInstallRequest;
import com.clawsaas.claw.dto.AgentInstancePatchRequest;
import com.clawsaas.claw.dto.AgentPackageInfo;
import com.clawsaas.claw.dto.AgentPackageVersionInfo;
import com.clawsaas.claw.exception.ApiException;
import com.clawsaas.claw.repository.AgentInstallApprovalRepository;
import com.clawsaas.claw.repository.ClawAgentRepository;
import com.clawsaas.claw.repository.ClawRepository;
import com.clawsaas.claw.repository.RouteBindingRepository;
import com.clawsaas.claw.service.AgentInstallService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentInstallServiceImpl implements AgentInstallService {

    private static final String SOURCE_TYPE_PACKAGE = "package";
    private static final String VERSION_STATUS_PUBLISHED = "published";
    private static final String VISIBILITY_PUBLIC = "public";

    private final ClawRepository claws;
    private final ClawAgentRepository clawAgents;
    private final AgentPackageClient agentPackageClient;
    private final AuditLogClient auditLogClient;
    private final AgentInstallApprovalRepository installApprovals;
    private final RouteBindingRepository routeBindings;

    public AgentInstallServiceImpl(
            ClawRepository claws,
            ClawAgentRepository clawAgents,
            AgentPackageClient agentPackageClient,
            AuditLogClient auditLogClient,
            AgentInstallApprovalRepository installApprovals,
            RouteBindingRepository routeBindings
    ) {
        this.claws = claws;
        this.clawAgents = clawAgents;
        this.agentPackageClient = agentPackageClient;
        this.auditLogClient = auditLogClient;
        this.installApprovals = installApprovals;
        this.routeBindings = routeBindings;
    }

    @Override
    @Transactional
    public ClawAgentEntity install(String clawId, AgentInstallRequest request, Authentication authentication) {
        String actorId = actorId(authentication);
        boolean admin = isAdmin(authentication);

        ClawEntity claw = claws.findById(clawId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claw not found"));
        if (!admin && !Objects.equals(claw.getOwnerUserId(), actorId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Claw not found");
        }

        AgentPackageVersionInfo version = agentPackageClient.getVersion(request.packageVersionId());
        if (version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Package version not found");
        }
        if (!VERSION_STATUS_PUBLISHED.equals(version.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Package version is not published");
        }

        AgentPackageInfo pkg = agentPackageClient.getPackage(version.packageId());
        if (pkg == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent package not found");
        }
        if (!admin && !Objects.equals(pkg.ownerUserId(), actorId) && !VISIBILITY_PUBLIC.equals(pkg.visibility())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Package version not found");
        }

        String roleKey = resolveRoleKey(clawId, request.roleKey(), pkg.packageKey());
        String displayName = request.displayName() != null && !request.displayName().isBlank()
                ? request.displayName().trim()
                : pkg.name();
        String localProfile = request.localProfile() != null && !request.localProfile().isBlank()
                ? request.localProfile().trim()
                : version.defaultProfile();

        OffsetDateTime now = OffsetDateTime.now();
        ClawAgentEntity instance = new ClawAgentEntity();
        instance.setId(UUID.randomUUID().toString());
        instance.setClawId(clawId);
        instance.setAgentId(version.id());
        instance.setRoleKey(roleKey);
        instance.setDisplayName(displayName);
        instance.setDefaultRole(false);
        instance.setEnabled(true);
        instance.setSortOrder(clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(clawId).size());
        instance.setSourceType(SOURCE_TYPE_PACKAGE);
        instance.setPackageId(pkg.id());
        instance.setPackageVersionId(version.id());
        instance.setLocalProfile(localProfile);
        instance.setInstalledBy(actorId);
        instance.setInstalledAt(now);
        instance.setCreatedAt(now);
        instance.setUpdatedAt(now);

        ClawAgentEntity saved = clawAgents.save(instance);

        audit(authentication, "agent_instance.install", saved.getId(), true, null);
        return saved;
    }

    @Override
    public List<ClawAgentEntity> listInstances(String clawId, Authentication authentication) {
        String actorId = actorId(authentication);
        boolean admin = isAdmin(authentication);

        ClawEntity claw = claws.findById(clawId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claw not found"));
        if (!admin && !Objects.equals(claw.getOwnerUserId(), actorId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Claw not found");
        }

        return clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(clawId);
    }

    @Override
    @Transactional
    public ClawAgentEntity updateInstance(String clawId, String agentInstanceId, AgentInstancePatchRequest request, Authentication authentication) {
        ClawAgentEntity instance = requireOwnedInstance(clawId, agentInstanceId, authentication);

        if (request.displayName() != null && !request.displayName().isBlank()) {
            instance.setDisplayName(request.displayName().trim());
        }
        if (request.roleKey() != null && !request.roleKey().isBlank()) {
            String newKey = normalizeKey(request.roleKey());
            if (!newKey.equals(instance.getRoleKey())) {
                ensureRoleKeyAvailable(clawId, newKey, instance.getId());
                instance.setRoleKey(newKey);
            }
        }
        if (request.localProfile() != null && !request.localProfile().isBlank()) {
            instance.setLocalProfile(request.localProfile().trim());
        }
        if (request.localSystemPromptOverride() != null) {
            instance.setLocalSystemPromptOverride(request.localSystemPromptOverride().isBlank() ? null : request.localSystemPromptOverride().trim());
        }
        if (request.enabled() != null) {
            instance.setEnabled(request.enabled());
        }
        if (request.defaultRole() != null) {
            instance.setDefaultRole(request.defaultRole());
        }
        if (request.sortOrder() != null) {
            instance.setSortOrder(request.sortOrder());
        }
        instance.setUpdatedAt(OffsetDateTime.now());
        ClawAgentEntity saved = clawAgents.save(instance);
        audit(authentication, "agent_instance.update", saved.getId(), true, null);
        return saved;
    }

    @Override
    @Transactional
    public void deleteInstance(String clawId, String agentInstanceId, Authentication authentication) {
        ClawAgentEntity instance = requireOwnedInstance(clawId, agentInstanceId, authentication);
        boolean deletedDefault = instance.isDefaultRole();
        if (instance.getRouteBindingId() != null) {
            routeBindings.findById(instance.getRouteBindingId()).ifPresent(routeBindings::delete);
            instance.setRouteBindingId(null);
        }
        clawAgents.delete(instance);
        if (deletedDefault) {
            promoteNextDefaultRole(clawId, agentInstanceId);
        }
        audit(authentication, "agent_instance.delete", agentInstanceId, true, null);
    }

    private void promoteNextDefaultRole(String clawId, String deletedInstanceId) {
        clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(clawId).stream()
                .filter(role -> !Objects.equals(role.getId(), deletedInstanceId))
                .filter(ClawAgentEntity::isEnabled)
                .findFirst()
                .ifPresent(role -> {
                    role.setDefaultRole(true);
                    role.setUpdatedAt(OffsetDateTime.now());
                    clawAgents.save(role);
                });
    }

    private ClawAgentEntity requireOwnedInstance(String clawId, String agentInstanceId, Authentication authentication) {
        String actorId = actorId(authentication);
        boolean admin = isAdmin(authentication);

        ClawEntity claw = claws.findById(clawId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claw not found"));
        if (!admin && !Objects.equals(claw.getOwnerUserId(), actorId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Claw not found");
        }

        ClawAgentEntity instance = clawAgents.findById(agentInstanceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent instance not found"));
        if (!Objects.equals(instance.getClawId(), clawId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent instance not found in this Claw");
        }
        return instance;
    }

    private String resolveRoleKey(String clawId, String requestedKey, String packageKey) {
        String base = requestedKey != null && !requestedKey.isBlank()
                ? normalizeKey(requestedKey)
                : normalizeKey(packageKey);
        if (!isRoleKeyTaken(clawId, base)) {
            return base;
        }
        for (int suffix = 2; suffix < 100; suffix++) {
            String candidate = base + "-" + suffix;
            if (!isRoleKeyTaken(clawId, candidate)) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private boolean isRoleKeyTaken(String clawId, String roleKey) {
        return clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(clawId).stream()
                .anyMatch(r -> roleKey.equals(r.getRoleKey()));
    }

    private void ensureRoleKeyAvailable(String clawId, String roleKey, String excludeInstanceId) {
        boolean conflict = clawAgents.findByClawIdOrderBySortOrderAscCreatedAtAsc(clawId).stream()
                .anyMatch(r -> roleKey.equals(r.getRoleKey()) && !r.getId().equals(excludeInstanceId));
        if (conflict) {
            throw new ApiException(HttpStatus.CONFLICT, "roleKey already exists in this Claw");
        }
    }

    private String normalizeKey(String value) {
        return value.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-");
    }

    @Override
    @Transactional
    public ClawAgentEntity approveInstall(String clawId, String approvalId, Authentication authentication) {
        String actorId = actorId(authentication);

        AgentInstallApprovalEntity approval = installApprovals.findByIdAndClawIdAndOwnerUserId(
                        approvalId, clawId, actorId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Install approval not found"));

        if (!AgentInstallApprovalStatus.PENDING.name().equals(approval.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Install approval is not pending (current status: " + approval.getStatus() + ")");
        }

        AgentInstallRequest installReq = new AgentInstallRequest(
                approval.getPackageVersionId(),
                null,
                null,
                null
        );
        ClawAgentEntity instance = install(approval.getClawId(), installReq, authentication);

        approval.setStatus(AgentInstallApprovalStatus.CONSUMED.name());
        approval.setResolvedAt(OffsetDateTime.now());
        installApprovals.save(approval);

        audit(authentication, "agent_install.approve", instance.getId(), true, null);
        return instance;
    }

    @Override
    @Transactional
    public void rejectInstall(String clawId, String approvalId, String reason, Authentication authentication) {
        String actorId = actorId(authentication);

        AgentInstallApprovalEntity approval = installApprovals.findByIdAndClawIdAndOwnerUserId(
                        approvalId, clawId, actorId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Install approval not found"));

        if (!AgentInstallApprovalStatus.PENDING.name().equals(approval.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Install approval is not pending (current status: " + approval.getStatus() + ")");
        }

        approval.setStatus(AgentInstallApprovalStatus.REJECTED.name());
        approval.setResolvedAt(OffsetDateTime.now());
        if (reason != null && !reason.isBlank()) {
            approval.setReason((approval.getReason() != null ? approval.getReason() + " | rejected: " : "rejected: ")
                    + reason.trim());
        }
        installApprovals.save(approval);

        audit(authentication, "agent_install.reject", approvalId, true, reason);
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
        auditLogClient.record(actorType(authentication), actorId(authentication), action, "agent_instance", resourceId, success, error);
    }
}
