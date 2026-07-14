package com.anxin.pyclaw.backend.sandbox;

import com.anxin.pyclaw.backend.audit.AuditLogService;
import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import com.anxin.pyclaw.backend.claw.ClawEntity;
import com.anxin.pyclaw.backend.claw.ClawRepository;
import com.anxin.pyclaw.backend.common.ApiException;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/claws/{clawId}/sandbox")
public class SandboxController {
    private static final Logger log = LoggerFactory.getLogger(SandboxController.class);
    private static final int MAX_FILE_BYTES = 1_048_576; // 1 MiB

    private final ClawRepository claws;
    private final SandboxClient sandboxClient;
    private final AuditLogService auditLogService;

    public SandboxController(ClawRepository claws, SandboxClient sandboxClient, AuditLogService auditLogService) {
        this.claws = claws;
        this.sandboxClient = sandboxClient;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/healthz")
    @PreAuthorize("hasAuthority('claw:read')")
    public ResponseEntity<String> healthz(@PathVariable String clawId, Authentication authentication) {
        ClawEntity claw = requireOwned(clawId, authentication);
        try {
            String result = sandboxClient.healthz(claw.getOwnerUserId(), clawId);
            return ResponseEntity.ok(result);
        } catch (SandboxClient.SandboxClientException e) {
            log.warn("sandbox healthz failed: claw_id={} error={}", clawId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @GetMapping("/workspace")
    @PreAuthorize("hasAuthority('claw:read')")
    public ResponseEntity<String> workspace(@PathVariable String clawId, Authentication authentication) {
        ClawEntity claw = requireOwned(clawId, authentication);
        try {
            String result = sandboxClient.getWorkspace(claw.getOwnerUserId(), clawId);
            return ResponseEntity.ok(result);
        } catch (SandboxClient.SandboxClientException e) {
            log.warn("sandbox workspace failed: claw_id={} error={}", clawId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @GetMapping("/files")
    @PreAuthorize("hasAuthority('claw:read')")
    public ResponseEntity<String> listFiles(
            @PathVariable String clawId,
            @RequestParam(defaultValue = ".") String path,
            Authentication authentication) {
        ClawEntity claw = requireOwned(clawId, authentication);
        try {
            String result = sandboxClient.listFiles(claw.getOwnerUserId(), clawId, path);
            return ResponseEntity.ok(result);
        } catch (SandboxClient.SandboxClientException e) {
            log.warn("sandbox list files failed: claw_id={} path={} error={}", clawId, path, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @GetMapping("/files/{filePath}")
    @PreAuthorize("hasAuthority('claw:read')")
    public ResponseEntity<String> getFile(
            @PathVariable String clawId,
            @PathVariable String filePath,
            Authentication authentication) {
        ClawEntity claw = requireOwned(clawId, authentication);
        try {
            String result = sandboxClient.getFile(claw.getOwnerUserId(), clawId, filePath);
            if (result != null && result.length() > MAX_FILE_BYTES) {
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                        .body("{\"error\":\"file exceeds max size: " + MAX_FILE_BYTES + " bytes\"}");
            }
            return ResponseEntity.ok(result);
        } catch (SandboxClient.SandboxClientException e) {
            log.warn("sandbox get file failed: claw_id={} file={} error={}", clawId, filePath, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @PutMapping("/files/{filePath}")
    @PreAuthorize("hasAuthority('claw:update')")
    public ResponseEntity<String> putFile(
            @PathVariable String clawId,
            @PathVariable String filePath,
            @RequestBody String content,
            Authentication authentication) {
        ClawEntity claw = requireOwned(clawId, authentication);
        if (content != null && content.length() > MAX_FILE_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body("{\"error\":\"file content exceeds max size: " + MAX_FILE_BYTES + " bytes\"}");
        }
        try {
            String result = sandboxClient.putFile(claw.getOwnerUserId(), clawId, filePath, content);
            return ResponseEntity.ok(result);
        } catch (SandboxClient.SandboxClientException e) {
            log.warn("sandbox put file failed: claw_id={} file={} error={}", clawId, filePath, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private ClawEntity requireOwned(String clawId, Authentication authentication) {
        ClawEntity claw = claws.findById(clawId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claw not found"));
        if (!isAdmin(authentication) && !Objects.equals(claw.getOwnerUserId(), actorId(authentication))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Claw not found");
        }
        return claw;
    }

    private boolean isAdmin(Authentication authentication) {
        Set<String> authorities = authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        return authorities.contains("user:manage");
    }

    private String actorId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.userId();
        }
        return null;
    }
}
