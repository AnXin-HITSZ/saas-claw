package com.anxin.pyclaw.backend.tool;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ToolCatalogService {
    private static final List<ToolCatalogEntryResponse> CATALOG = List.of(
            new ToolCatalogEntryResponse("read", "Read", "Read a UTF-8 text file from the workspace.", "filesystem", List.of("readonly", "coding", "full"), List.of("fs", "read", "readonly"), "low", true),
            new ToolCatalogEntryResponse("list_dir", "List Directory", "List files and directories inside the workspace.", "filesystem", List.of("readonly", "coding", "full"), List.of("fs", "list", "readonly"), "low", true),
            new ToolCatalogEntryResponse("ls", "Ls", "Alias for list_dir.", "filesystem", List.of("readonly", "coding", "full"), List.of("fs", "list", "readonly", "alias"), "low", true),
            new ToolCatalogEntryResponse("grep", "Grep", "Search workspace files.", "filesystem", List.of("readonly", "coding", "full"), List.of("fs", "search", "readonly"), "low", true),
            new ToolCatalogEntryResponse("find", "Find", "Find files or directories by name.", "filesystem", List.of("readonly", "coding", "full"), List.of("fs", "find", "readonly"), "low", true),
            new ToolCatalogEntryResponse("write", "Write", "Write text to a workspace file.", "filesystem", List.of("coding", "full"), List.of("fs", "write", "mutation"), "medium", true),
            new ToolCatalogEntryResponse("edit", "Edit", "Replace exact text in a workspace file.", "filesystem", List.of("coding", "full"), List.of("fs", "edit", "mutation"), "medium", true),
            new ToolCatalogEntryResponse("apply_patch", "Apply Patch", "Apply a conservative exact-text patch.", "filesystem", List.of("coding", "full"), List.of("fs", "edit", "patch", "mutation"), "medium", true),
            new ToolCatalogEntryResponse("shell", "Shell", "Execute a shell command inside the workspace.", "runtime", List.of("full"), List.of("runtime", "shell", "exec", "mutation", "high-risk"), "high", true),
            new ToolCatalogEntryResponse("exec", "Exec", "OpenClaw-compatible shell command execution.", "runtime", List.of("full"), List.of("runtime", "shell", "exec", "mutation", "high-risk"), "high", true),
            new ToolCatalogEntryResponse("web_fetch", "Web Fetch", "Fetch a public HTTP(S) URL.", "web", List.of("full"), List.of("web", "fetch", "network"), "medium", false),
            new ToolCatalogEntryResponse("web_search", "Web Search", "Search the public web.", "web", List.of("full"), List.of("web", "search", "network"), "medium", false)
    );
    private static final Map<String, Set<String>> PROFILE_TAGS = Map.of(
            "minimal", Set.of(),
            "readonly", Set.of("readonly"),
            "coding", Set.of("readonly", "coding"),
            "messaging", Set.of("messaging"),
            "full", Set.of("readonly", "coding", "messaging", "full")
    );

    public List<ToolCatalogEntryResponse> catalog() {
        return CATALOG;
    }

    public List<String> profiles() {
        return List.of("minimal", "readonly", "messaging", "coding", "full");
    }

    public EffectiveToolsResponse effective(EffectiveToolsRequest request) {
        String profile = normalize(request.profile(), "coding");
        Set<String> allow = request.allow() == null ? null : expand(request.allow());
        Set<String> deny = expand(request.deny());
        Set<String> alsoAllow = expand(request.alsoAllow());
        Set<String> profileTags = PROFILE_TAGS.getOrDefault(profile, PROFILE_TAGS.get("coding"));
        boolean readonly = Boolean.TRUE.equals(request.readonly()) || "readonly".equals(profile);
        List<String> selected = CATALOG.stream()
                .filter(tool -> allow == null
                        ? alsoAllow.contains(tool.name()) || tool.profiles().isEmpty() || intersects(profileTags, tool.profiles())
                        : allow.contains(tool.name()) || alsoAllow.contains(tool.name()))
                .filter(tool -> !readonly || tool.tags().contains("readonly"))
                .filter(tool -> !deny.contains(tool.name()))
                .map(ToolCatalogEntryResponse::name)
                .sorted()
                .toList();
        List<String> denied = CATALOG.stream()
                .map(ToolCatalogEntryResponse::name)
                .filter(name -> !selected.contains(name))
                .sorted()
                .toList();
        return new EffectiveToolsResponse(profile, selected, denied);
    }

    private boolean intersects(Set<String> tags, List<String> profiles) {
        for (String profile : profiles) {
            if (tags.contains(profile)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> expand(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        Map<String, Set<String>> groups = groups();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> normalize(value, ""))
                .flatMap(value -> groups.getOrDefault(value, Set.of(value)).stream())
                .collect(Collectors.toSet());
    }

    private Map<String, Set<String>> groups() {
        Map<String, Set<String>> groups = CATALOG.stream()
                .collect(Collectors.groupingBy(
                        entry -> "group:" + entry.sectionId().toLowerCase(Locale.ROOT),
                        Collectors.mapping(ToolCatalogEntryResponse::name, Collectors.toSet())
                ));
        for (ToolCatalogEntryResponse entry : CATALOG) {
            for (String tag : entry.tags()) {
                groups.computeIfAbsent("group:" + tag.toLowerCase(Locale.ROOT), ignored -> new java.util.HashSet<>()).add(entry.name());
            }
        }
        if (groups.containsKey("group:fs")) {
            groups.computeIfAbsent("group:filesystem", ignored -> new java.util.HashSet<>()).addAll(groups.get("group:fs"));
        }
        if (groups.containsKey("group:runtime")) {
            groups.computeIfAbsent("group:shell", ignored -> new java.util.HashSet<>()).addAll(groups.get("group:runtime"));
        }
        return groups;
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }
}
