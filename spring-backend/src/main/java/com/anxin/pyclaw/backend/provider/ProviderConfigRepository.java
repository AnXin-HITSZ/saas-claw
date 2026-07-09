package com.anxin.pyclaw.backend.provider;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderConfigRepository extends JpaRepository<ProviderConfigEntity, String> {
    ProviderConfigEntity findFirstByNameIgnoreCaseAndEnabledTrue(String name);

    ProviderConfigEntity findFirstByProviderTypeIgnoreCaseAndEnabledTrue(String providerType);
}
