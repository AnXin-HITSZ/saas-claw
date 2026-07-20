package com.clawsaas.claw.client;

import com.clawsaas.claw.dto.AgentPackageInfo;
import com.clawsaas.claw.dto.AgentPackageVersionInfo;

/**
 * Stub for agent-marketplace-service package queries.
 */
public interface AgentPackageClient {
    AgentPackageInfo getPackage(String id);
    AgentPackageVersionInfo getVersion(String versionId);
}
