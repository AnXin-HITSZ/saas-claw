package com.clawsaas.agentmarketplace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.clawsaas.agentmarketplace.service.impl.AgentMarketplaceServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AgentMarketplaceServiceTest {

    private static final String OWNER_ID = "user-a";
    private static final String OTHER_ID = "user-b";

    private AgentPackageRepository packages;
    private AgentPackageVersionRepository versions;
    private AgentConfigClient agentConfigClient;
    private AgentToolPolicyClient agentToolPolicyClient;
    private AuditLogClient auditLogClient;
    private AgentMarketplaceServiceImpl service;

    @BeforeEach
    void setUp() {
        packages = mock(AgentPackageRepository.class);
        versions = mock(AgentPackageVersionRepository.class);
        agentConfigClient = mock(AgentConfigClient.class);
        agentToolPolicyClient = mock(AgentToolPolicyClient.class);
        auditLogClient = mock(AuditLogClient.class);
        service = new AgentMarketplaceServiceImpl(packages, versions, agentConfigClient, agentToolPolicyClient, new ObjectMapper(), auditLogClient);
    }

    @Test
    void nonOwnerCannotPublish() {
        AgentConfigDTO agent = baseAgent(OWNER_ID);
        when(agentConfigClient.findById("agent-1")).thenReturn(agent);

        assertThatThrownBy(() -> service.publish("agent-1", publishRequest(), auth(OTHER_ID, false)))
                .isInstanceOfSatisfying(ApiException.class, exc -> assertThat(exc.status()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(packages, never()).save(any());
    }

    @Test
    void duplicateVersionIsRejected() {
        AgentConfigDTO agent = baseAgent(OWNER_ID);
        when(agentConfigClient.findById("agent-1")).thenReturn(agent);
        when(agentToolPolicyClient.findByAgentId("agent-1")).thenReturn(policy("messaging"));

        AgentPackageEntity pkg = existingPackage("pkg-1", OWNER_ID, "k3s-troubleshooter");
        when(packages.findByOwnerUserIdAndPackageKey(OWNER_ID, "k3s-troubleshooter")).thenReturn(Optional.of(pkg));
        when(versions.findByPackageIdAndVersion("pkg-1", "1.0.0")).thenReturn(Optional.of(existingVersion("pkg-1", "1.0.0")));

        assertThatThrownBy(() -> service.publish("agent-1", publishRequest(), auth(OWNER_ID, false)))
                .isInstanceOfSatisfying(ApiException.class, exc -> assertThat(exc.status()).isEqualTo(HttpStatus.CONFLICT));
        verify(versions, never()).save(any());
    }

    @Test
    void publishCreatesNewVersionAndUpdatesLatest() {
        AgentConfigDTO agent = baseAgent(OWNER_ID);
        when(agentConfigClient.findById("agent-1")).thenReturn(agent);
        when(agentToolPolicyClient.findByAgentId("agent-1")).thenReturn(policy("messaging"));

        AgentPackageEntity pkg = existingPackage("pkg-1", OWNER_ID, "k3s-troubleshooter");
        when(packages.findByOwnerUserIdAndPackageKey(OWNER_ID, "k3s-troubleshooter")).thenReturn(Optional.of(pkg));
        when(versions.findByPackageIdAndVersion("pkg-1", "1.0.0")).thenReturn(Optional.empty());
        when(packages.save(any(AgentPackageEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versions.save(any(AgentPackageVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentPackageVersionResponse response = service.publish("agent-1", publishRequest(), auth(OWNER_ID, false));

        assertThat(response.status()).isEqualTo("published");
        assertThat(response.version()).isEqualTo("1.0.0");
        assertThat(response.defaultProfile()).isEqualTo("messaging");
        assertThat(pkg.getLatestVersionId()).isEqualTo(response.id());
        assertThat(pkg.getVisibility()).isEqualTo("public");
        verify(auditLogClient).record(eq("USER"), eq(OWNER_ID), eq("agent_package.publish"), eq("agent_package"), eq(response.id()), eq(true), any());
    }

    @Test
    void publicPackageIsListedForEveryone() {
        AgentPackageEntity pub = existingPackage("pkg-1", OWNER_ID, "k3s-troubleshooter");
        pub.setVisibility("public");
        when(packages.findByVisibilityOrderByUpdatedAtDesc("public")).thenReturn(List.of(pub));

        List<AgentPackageResponse> rows = service.list(auth(OTHER_ID, false));
        assertThat(rows).extracting(AgentPackageResponse::id).contains("pkg-1");
    }

    @Test
    void privatePackageOnlyVisibleToOwner() {
        AgentPackageEntity priv = existingPackage("pkg-1", OWNER_ID, "k3s-troubleshooter");
        priv.setVisibility("private");
        when(packages.findById("pkg-1")).thenReturn(Optional.of(priv));

        assertThatThrownBy(() -> service.get("pkg-1", auth(OTHER_ID, false)))
                .isInstanceOfSatisfying(ApiException.class, exc -> assertThat(exc.status()).isEqualTo(HttpStatus.NOT_FOUND));

        when(packages.findByOwnerUserIdOrderByUpdatedAtDesc(OWNER_ID)).thenReturn(List.of(priv));
        assertThat(service.get("pkg-1", auth(OWNER_ID, false)).id()).isEqualTo("pkg-1");
    }

    private AgentPublishRequest publishRequest() {
        return new AgentPublishRequest("k3s-troubleshooter", "1.0.0", "public", "排查 K3s 问题", "首次发布");
    }

    private AgentConfigDTO baseAgent(String ownerId) {
        return new AgentConfigDTO(
                "agent-1",
                "k3s-troubleshooter",
                "K3s 排障助手",
                "排查 K3s、Helm、Pod 问题",
                null,
                null,
                "You are a K3s troubleshooter.",
                ownerId
        );
    }

    private AgentToolPolicyDTO policy(String profile) {
        return new AgentToolPolicyDTO("policy-1", "agent-1", profile, "readonly".equals(profile));
    }

    private AgentPackageEntity existingPackage(String id, String ownerId, String packageKey) {
        AgentPackageEntity pkg = new AgentPackageEntity();
        pkg.setId(id);
        pkg.setOwnerUserId(ownerId);
        pkg.setPackageKey(packageKey);
        pkg.setName("K3s 排障助手");
        pkg.setVisibility("private");
        pkg.setInstallCount(0L);
        pkg.setCreatedAt(OffsetDateTime.now().minusMinutes(5));
        pkg.setUpdatedAt(OffsetDateTime.now().minusMinutes(5));
        return pkg;
    }

    private AgentPackageVersionEntity existingVersion(String packageId, String version) {
        AgentPackageVersionEntity ver = new AgentPackageVersionEntity();
        ver.setId("ver-old");
        ver.setPackageId(packageId);
        ver.setVersion(version);
        ver.setStatus("published");
        ver.setDefaultProfile("messaging");
        ver.setCreatedAt(OffsetDateTime.now().minusDays(1));
        return ver;
    }

    private Authentication auth(String userId, boolean admin) {
        Collection<GrantedAuthority> authorities = admin
                ? List.of(new SimpleGrantedAuthority("user:manage"), new SimpleGrantedAuthority("agent:read"))
                : List.of(new SimpleGrantedAuthority("agent:read"));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(userId, userId, "USER", authorities);
        return new StubAuthentication(principal, authorities);
    }

    /**
     * Concrete Authentication implementation to avoid Mockito's limitations
     * when mocking Spring Security interfaces on Java 24+.
     */
    private static class StubAuthentication implements Authentication {
        private final AuthenticatedPrincipal principal;
        private final Collection<? extends GrantedAuthority> authorities;

        StubAuthentication(AuthenticatedPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
            this.principal = principal;
            this.authorities = authorities;
        }

        @Override
        public String getName() { return principal.getUsername(); }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }

        @Override
        public Object getCredentials() { return null; }

        @Override
        public Object getDetails() { return null; }

        @Override
        public Object getPrincipal() { return principal; }

        @Override
        public boolean isAuthenticated() { return true; }

        @Override
        public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {}
    }
}
