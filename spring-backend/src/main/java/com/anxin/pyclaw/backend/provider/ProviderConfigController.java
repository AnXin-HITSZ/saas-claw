package com.anxin.pyclaw.backend.provider;

import com.anxin.pyclaw.backend.common.ApiException;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/providers")
public class ProviderConfigController {
    private final ProviderConfigRepository repository;

    public ProviderConfigController(ProviderConfigRepository repository) {
        this.repository = repository;
    }


    @GetMapping("/options")
    @PreAuthorize("hasAuthority('provider:manage') or hasAuthority('agent:read') or hasAuthority('agent:update')")
    public List<ProviderOptionResponse> options() {
        return repository.findAll().stream()
                .map(ProviderOptionResponse::from)
                .toList();
    }
    @GetMapping
    @PreAuthorize("hasAuthority('provider:manage') or hasAuthority('agent:run')")
    public List<ProviderConfigResponse> list() {
        return repository.findAll().stream()
                .map(ProviderConfigResponse::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('provider:manage')")
    public ProviderConfigResponse create(@Valid @RequestBody ProviderConfigRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        ProviderConfigEntity entity = new ProviderConfigEntity();
        entity.setId(UUID.randomUUID().toString());
        apply(entity, request);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return ProviderConfigResponse.from(repository.save(entity));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('provider:manage')")
    public ProviderConfigResponse update(@PathVariable String id, @Valid @RequestBody ProviderConfigRequest request) {
        ProviderConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Provider config not found"));
        apply(entity, request);
        entity.setUpdatedAt(OffsetDateTime.now());
        return ProviderConfigResponse.from(repository.save(entity));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('provider:manage')")
    public void delete(@PathVariable String id) {
        repository.deleteById(id);
    }

    private void apply(ProviderConfigEntity entity, ProviderConfigRequest request) {
        entity.setName(request.name());
        entity.setProviderType(request.providerType());
        entity.setBaseUrl(request.baseUrl());
        entity.setModel(request.model());
        entity.setApiMode(request.apiMode());
        entity.setSecretRef(request.secretRef());
        if (request.clearApiKey()) {
            entity.setApiKey(null);
        }
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            entity.setApiKey(request.apiKey().trim());
        }
        entity.setEnabled(request.enabled());
    }
}
