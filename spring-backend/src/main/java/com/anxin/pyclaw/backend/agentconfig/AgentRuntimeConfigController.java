package com.anxin.pyclaw.backend.agentconfig;

import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.config.PyclawRuntimeProperties;
import com.anxin.pyclaw.backend.provider.ProviderConfigEntity;
import com.anxin.pyclaw.backend.provider.ProviderConfigRepository;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/agents")
public class AgentRuntimeConfigController {
    private final AgentConfigService agents;
    private final ProviderConfigRepository providers;
    private final PyclawRuntimeProperties properties;

    public AgentRuntimeConfigController(
            AgentConfigService agents,
            ProviderConfigRepository providers,
            PyclawRuntimeProperties properties
    ) {
        this.agents = agents;
        this.providers = providers;
        this.properties = properties;
    }

    @GetMapping("/{agentKey}/runtime-config")
    public AgentRuntimeConfigResponse runtimeConfig(
            @PathVariable String agentKey,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        requireInternalToken(authorization);
        AgentConfigEntity agent = agents.requireEnabledByKey(agentKey);
        AgentToolPolicyEntity policy = agents.requirePolicy(agent.getId());
        ProviderConfigEntity providerConfig = resolveProviderConfig(agent);
        String providerType = firstNonBlank(agent.getProvider(), providerConfig == null ? null : providerConfig.getProviderType(), "openai");
        if (providerType.toLowerCase(Locale.ROOT).contains("openai")) {
            providerType = "openai";
        }
        return new AgentRuntimeConfigResponse(
                agent.getId(),
                agent.getAgentKey(),
                agent.getName(),
                agent.isEnabled(),
                providerType,
                firstNonBlank(agent.getModel(), providerConfig == null ? null : providerConfig.getModel()),
                firstNonBlank(providerConfig == null ? null : providerConfig.getApiMode(), "auto"),
                providerConfig == null ? null : providerConfig.getBaseUrl(),
                providerConfig == null ? null : providerConfig.getApiKey(),
                agent.getSystemPrompt(),
                agent.getWorkspaceDir(),
                agent.getRuntimeType(),
                new AgentRuntimeToolPolicyResponse(
                        policy.getProfile(),
                        agents.readListOrNull(policy.getToolsAllowJson()),
                        agents.readList(policy.getToolsDenyJson()),
                        agents.readList(policy.getToolsAlsoAllowJson()),
                        policy.isWorkspaceOnly(),
                        policy.isReadonly(),
                        policy.getShellApproval(),
                        policy.isWebAccess()
                ),
                agent.getUpdatedAt().toInstant().toString() + ":" + policy.getUpdatedAt().toInstant(),
                agent.getUpdatedAt().isAfter(policy.getUpdatedAt()) ? agent.getUpdatedAt() : policy.getUpdatedAt()
        );
    }

    private ProviderConfigEntity resolveProviderConfig(AgentConfigEntity agent) {
        if (agent.getProviderId() != null && !agent.getProviderId().isBlank()) {
            return providers.findById(agent.getProviderId()).orElse(null);
        }
        String requestedProvider = agent.getProvider();
        if (requestedProvider == null || requestedProvider.isBlank()) {
            ProviderConfigEntity compatible = providers.findFirstByProviderTypeIgnoreCaseAndEnabledTrueOrderByUpdatedAtDesc("openai-compatible");
            return compatible == null ? providers.findFirstByProviderTypeIgnoreCaseAndEnabledTrueOrderByUpdatedAtDesc("openai") : compatible;
        }
        ProviderConfigEntity byName = providers.findFirstByNameIgnoreCaseAndEnabledTrue(requestedProvider);
        if (byName != null) {
            return byName;
        }
        ProviderConfigEntity byType = providers.findFirstByProviderTypeIgnoreCaseAndEnabledTrue(requestedProvider);
        if (byType != null) {
            return byType;
        }
        if ("openai".equalsIgnoreCase(requestedProvider)) {
            return providers.findFirstByProviderTypeIgnoreCaseAndEnabledTrueOrderByUpdatedAtDesc("openai-compatible");
        }
        return null;
    }
    private void requireInternalToken(String authorization) {
        String expected = properties.internalToken();
        if (expected == null || expected.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Internal runtime config token is not configured");
        }
        String prefix = "Bearer ";
        if (authorization == null || !authorization.startsWith(prefix)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing internal API token");
        }
        String actual = authorization.substring(prefix.length());
        if (!expected.equals(actual)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid internal API token");
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
