package com.clawsaas.claw.controller;

import com.clawsaas.claw.client.ProviderConfigClient;
import com.clawsaas.claw.domain.AgentConfigEntity;
import com.clawsaas.claw.domain.AgentToolPolicyEntity;
import com.clawsaas.claw.domain.ProviderConfigEntity;
import com.clawsaas.claw.dto.AgentRuntimeConfigResponse;
import com.clawsaas.claw.dto.AgentRuntimeToolPolicyResponse;
import com.clawsaas.claw.exception.ApiException;
import com.clawsaas.claw.service.AgentConfigService;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
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
    private final ProviderConfigClient providerConfigClient;
    private final String internalToken;

    public AgentRuntimeConfigController(
            AgentConfigService agents,
            ProviderConfigClient providerConfigClient,
            @Value("${claw.runtime.internal-token:}") String internalToken
    ) {
        this.agents = agents;
        this.providerConfigClient = providerConfigClient;
        this.internalToken = internalToken;
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
        String apiKey = providerConfig == null ? null : providerConfigClient.getDecryptedApiKey(providerConfig);
        return new AgentRuntimeConfigResponse(
                agent.getId(),
                agent.getAgentKey(),
                agent.getName(),
                agent.isEnabled(),
                providerType,
                firstNonBlank(agent.getModel(), providerConfig == null ? null : providerConfig.getModel()),
                firstNonBlank(providerConfig == null ? null : providerConfig.getApiMode(), "auto"),
                providerConfig == null ? null : providerConfig.getBaseUrl(),
                apiKey,
                agent.getSystemPrompt(),
                agent.getWorkspaceDir(),
                agent.getRuntimeType(),
                new AgentRuntimeToolPolicyResponse(
                        policy.getProfile(),
                        agents.readListOrNull(policy.getToolsAllowJson()),
                        agents.readList(policy.getToolsDenyJson()),
                        agents.readList(policy.getToolsAlsoAllowJson()),
                        policy.isReadonly()
                ),
                agent.getUpdatedAt().toInstant().toString() + ":" + policy.getUpdatedAt().toInstant(),
                agent.getUpdatedAt().isAfter(policy.getUpdatedAt()) ? agent.getUpdatedAt() : policy.getUpdatedAt()
        );
    }

    private ProviderConfigEntity resolveProviderConfig(AgentConfigEntity agent) {
        if (agent.getProviderId() != null && !agent.getProviderId().isBlank()) {
            return providerConfigClient.findById(agent.getProviderId());
        }
        String requestedProvider = agent.getProvider();
        if (requestedProvider == null || requestedProvider.isBlank()) {
            ProviderConfigEntity compatible = providerConfigClient.findFirstByProviderTypeIgnoreCaseAndEnabledTrueOrderByUpdatedAtDesc("openai-compatible");
            return compatible == null ? providerConfigClient.findFirstByProviderTypeIgnoreCaseAndEnabledTrueOrderByUpdatedAtDesc("openai") : compatible;
        }
        ProviderConfigEntity byName = providerConfigClient.findFirstByNameIgnoreCaseAndEnabledTrue(requestedProvider);
        if (byName != null) {
            return byName;
        }
        ProviderConfigEntity byType = providerConfigClient.findFirstByProviderTypeIgnoreCaseAndEnabledTrueOrderByUpdatedAtDesc(requestedProvider);
        if (byType != null) {
            return byType;
        }
        if ("openai".equalsIgnoreCase(requestedProvider)) {
            return providerConfigClient.findFirstByProviderTypeIgnoreCaseAndEnabledTrueOrderByUpdatedAtDesc("openai-compatible");
        }
        return null;
    }

    private void requireInternalToken(String authorization) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Internal runtime config token is not configured");
        }
        String prefix = "Bearer ";
        if (authorization == null || !authorization.startsWith(prefix)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing internal API token");
        }
        String actual = authorization.substring(prefix.length());
        if (!internalToken.equals(actual)) {
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
