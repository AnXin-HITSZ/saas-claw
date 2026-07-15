package com.anxin.pyclaw.backend.tool;

import com.anxin.pyclaw.backend.pyclaw.PyclawDeniedTool;
import com.anxin.pyclaw.backend.pyclaw.PyclawToolCatalogEntry;
import com.anxin.pyclaw.backend.pyclaw.PyclawToolCatalogResponse;
import com.anxin.pyclaw.backend.pyclaw.PyclawToolResolveRequest;
import com.anxin.pyclaw.backend.pyclaw.PyclawToolResolveResponse;
import com.anxin.pyclaw.backend.pyclaw.PyclawClient;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ToolCatalogService {
    private final PyclawClient pyclawClient;

    public ToolCatalogService(PyclawClient pyclawClient) {
        this.pyclawClient = pyclawClient;
    }

    public List<ToolCatalogEntryResponse> catalog() {
        return pyclawClient.toolCatalog().tools().stream()
                .map(this::toResponse)
                .toList();
    }

    public List<String> profiles() {
        PyclawToolCatalogResponse response = pyclawClient.toolCatalog();
        return response.profiles() == null || response.profiles().isEmpty()
                ? List.of("minimal", "readonly", "messaging", "coding", "full")
                : response.profiles();
    }

    public EffectiveToolsResponse effective(EffectiveToolsRequest request) {
        PyclawToolResolveResponse resolved = pyclawClient.resolveTools(new PyclawToolResolveRequest(
                normalizeProfile(request == null ? null : request.profile()),
                request == null ? null : request.allow(),
                request == null ? List.of() : request.deny(),
                request == null ? List.of() : request.alsoAllow(),
                request != null && Boolean.TRUE.equals(request.readonly()),
                request == null || request.workspaceMode() == null || request.workspaceMode().isBlank()
                        ? "sandbox_runner"
                        : request.workspaceMode(),
                request != null && Boolean.TRUE.equals(request.webAccess())
        ));
        return new EffectiveToolsResponse(
                resolved.profile(),
                resolved.tools().stream().map(PyclawToolCatalogEntry::name).sorted().toList(),
                resolved.deniedTools().stream().map(PyclawDeniedTool::name).sorted().toList()
        );
    }

    private ToolCatalogEntryResponse toResponse(PyclawToolCatalogEntry entry) {
        return new ToolCatalogEntryResponse(
                entry.name(),
                entry.label(),
                entry.description(),
                entry.sectionId(),
                entry.sectionId(),
                entry.profiles(),
                entry.tags(),
                entry.risk(),
                entry.workspaceOnly(),
                entry.workspaceModes(),
                entry.readonly(),
                entry.requiresApproval(),
                entry.promptHint()
        );
    }

    private String normalizeProfile(String profile) {
        return profile == null || profile.isBlank() ? "coding" : profile.trim().toLowerCase().replace('-', '_');
    }
}
