package com.anxin.pyclaw.backend.pyclaw;

import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.config.PyclawRuntimeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class PyclawClient {
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "content-length",
            "expect",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
    );

    private final PyclawRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PyclawClient(PyclawRuntimeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build();
    }

    public PyclawAgentRunResponse runAgent(PyclawAgentRunRequest request) {
        return postForAgentRunResponse("/v1/agent/run", request, "pyclaw call failed");
    }

    public PyclawAgentRunResponse resumeAgent(PyclawAgentResumeRequest request) {
        return postForAgentRunResponse("/v1/agent/resume", request, "pyclaw resume call failed");
    }

    private PyclawAgentRunResponse postForAgentRunResponse(String path, Object requestBody, String failureMessage) {
        try {
            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.baseUrl()) + path))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(properties.readTimeoutSeconds()))
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            addInternalAuthorization(builder);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, failureMessage + ": " + response.body());
            }
            return objectMapper.readValue(response.body(), PyclawAgentRunResponse.class);
        } catch (ApiException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, failureMessage + ": " + exc.getMessage());
        }
    }


    public PyclawToolCatalogResponse toolCatalog() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.baseUrl()) + "/v1/tools/catalog"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(properties.readTimeoutSeconds()))
                    .GET();
            addInternalAuthorization(builder);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "pyclaw tool catalog failed: " + response.body());
            }
            return objectMapper.readValue(response.body(), PyclawToolCatalogResponse.class);
        } catch (ApiException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "pyclaw tool catalog failed: " + exc.getMessage());
        }
    }

    public PyclawToolResolveResponse resolveTools(PyclawToolResolveRequest request) {
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.baseUrl()) + "/v1/tools/resolve"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(properties.readTimeoutSeconds()))
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            addInternalAuthorization(builder);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "pyclaw tool resolve failed: " + response.body());
            }
            return objectMapper.readValue(response.body(), PyclawToolResolveResponse.class);
        } catch (ApiException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "pyclaw tool resolve failed: " + exc.getMessage());
        }
    }
    public ResponseEntity<byte[]> forwardChannelWebhook(
            String channel,
            String queryString,
            byte[] body,
            String method,
            Map<String, List<String>> incomingHeaders
    ) {
        try {
            URI uri = URI.create(webhookUrl(channel, queryString));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(properties.readTimeoutSeconds()));
            copyForwardableHeaders(builder, incomingHeaders);
            addInternalAuthorization(builder);

            byte[] requestBody = body == null ? new byte[0] : body;
            if ("GET".equalsIgnoreCase(method)) {
                builder.GET();
            } else {
                builder.method(method.toUpperCase(Locale.ROOT), HttpRequest.BodyPublishers.ofByteArray(requestBody));
            }

            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            HttpHeaders responseHeaders = new HttpHeaders();
            response.headers().map().forEach((name, values) -> {
                if (isForwardableResponseHeader(name)) {
                    responseHeaders.put(name, values);
                }
            });
            return ResponseEntity.status(response.statusCode()).headers(responseHeaders).body(response.body());
        } catch (Exception exc) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "pyclaw webhook proxy failed: " + exc.getMessage());
        }
    }

    private String webhookUrl(String channel, String queryString) {
        String url = trimTrailingSlash(properties.baseUrl()) + "/v1/channels/" + channel + "/webhook";
        if (queryString != null && !queryString.isBlank()) {
            return url + "?" + queryString;
        }
        return url;
    }

    private void copyForwardableHeaders(HttpRequest.Builder builder, Map<String, List<String>> incomingHeaders) {
        if (incomingHeaders == null) {
            return;
        }
        incomingHeaders.forEach((name, values) -> {
            if (!isForwardableRequestHeader(name) || values == null) {
                return;
            }
            for (String value : values) {
                if (value != null) {
                    builder.header(name, value);
                }
            }
        });
    }

    private boolean isForwardableRequestHeader(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !HOP_BY_HOP_HEADERS.contains(lower) && !HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name);
    }

    private boolean isForwardableResponseHeader(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !HOP_BY_HOP_HEADERS.contains(lower);
    }

    private void addInternalAuthorization(HttpRequest.Builder builder) {
        if (properties.apiToken() != null && !properties.apiToken().isBlank()) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken());
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
