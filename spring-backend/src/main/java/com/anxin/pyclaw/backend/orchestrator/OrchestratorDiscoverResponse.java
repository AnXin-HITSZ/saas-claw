package com.anxin.pyclaw.backend.orchestrator;

public record OrchestratorDiscoverResponse(
        String packageId,
        String packageVersionId,
        String packageKey,
        String name,
        String summary,
        String version,
        String defaultProfile,
        String capabilities
) {}
