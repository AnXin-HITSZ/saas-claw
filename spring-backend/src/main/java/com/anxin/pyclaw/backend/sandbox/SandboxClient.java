package com.anxin.pyclaw.backend.sandbox;

import com.anxin.pyclaw.backend.config.PyclawRuntimeProperties;
import com.anxin.pyclaw.backend.config.PyclawSandboxProperties;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for communicating with sandbox-runner Pods inside user namespaces.
 * Service URL format: http://sandbox-runner-{clawId}.pyclaw-user-{userId}.svc.cluster.local:8000
 */
@Component
public class SandboxClient {
    private static final Logger log = LoggerFactory.getLogger(SandboxClient.class);

    private final PyclawSandboxProperties sandboxProperties;
    private final RestClient restClient;

    public SandboxClient(PyclawSandboxProperties sandboxProperties, PyclawRuntimeProperties runtimeProperties) {
        this.sandboxProperties = sandboxProperties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(runtimeProperties.connectTimeoutSeconds()));
        factory.setReadTimeout(java.time.Duration.ofSeconds(runtimeProperties.readTimeoutSeconds()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public String serviceUrl(String userId, String clawId) {
        String namespace = dnsName(sandboxProperties.getNamespacePrefix() + "-" + userId);
        String svcName = dnsName("sandbox-runner-" + clawId);
        return String.format("http://%s.%s.svc.cluster.local:%d", svcName, namespace, sandboxProperties.getRunnerPort());
    }

    // ---- Health ----

    public String healthz(String userId, String clawId) {
        String url = serviceUrl(userId, clawId) + "/healthz";
        log.debug("sandbox healthz: url={}", url);
        return restClient.get().uri(url).retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new SandboxClientException("runner healthz failed: status=" + res.getStatusCode());
                })
                .body(String.class);
    }

    // ---- Workspace ----

    public String getWorkspace(String userId, String clawId) {
        String url = serviceUrl(userId, clawId) + "/v1/workspace";
        log.debug("sandbox workspace: url={}", url);
        return restClient.get().uri(url).retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new SandboxClientException("runner workspace failed: status=" + res.getStatusCode());
                })
                .body(String.class);
    }

    // ---- Files ----

    public String listFiles(String userId, String clawId, String path) {
        String safePath = path == null || path.isBlank() ? "." : path;
        String url = serviceUrl(userId, clawId) + "/v1/workspace/files?path=" + safePath;
        log.debug("sandbox list files: url={}", url);
        return restClient.get().uri(url).retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new SandboxClientException("runner list files failed: status=" + res.getStatusCode());
                })
                .body(String.class);
    }

    public String getFile(String userId, String clawId, String filePath) {
        String url = serviceUrl(userId, clawId) + "/v1/workspace/files/" + filePath;
        log.debug("sandbox get file: url={}", url);
        return restClient.get().uri(url).retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new SandboxClientException("runner get file failed: status=" + res.getStatusCode());
                })
                .body(String.class);
    }

    public String putFile(String userId, String clawId, String filePath, String content) {
        String url = serviceUrl(userId, clawId) + "/v1/workspace/files/" + filePath;
        log.debug("sandbox put file: url={}", url);
        return restClient.put().uri(url).body(content).retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new SandboxClientException("runner put file failed: status=" + res.getStatusCode());
                })
                .body(String.class);
    }

    private String dnsName(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (normalized.isBlank()) {
            normalized = "x";
        }
        if (normalized.length() > 63) {
            normalized = normalized.substring(0, 63).replaceAll("-$", "");
        }
        return normalized;
    }

    public static class SandboxClientException extends RuntimeException {
        public SandboxClientException(String message) {
            super(message);
        }
    }
}
