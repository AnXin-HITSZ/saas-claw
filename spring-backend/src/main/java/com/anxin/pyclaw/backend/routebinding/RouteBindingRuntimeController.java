package com.anxin.pyclaw.backend.routebinding;

import com.anxin.pyclaw.backend.common.ApiException;
import com.anxin.pyclaw.backend.config.PyclawRuntimeProperties;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/route-bindings")
public class RouteBindingRuntimeController {
    private final RouteBindingService service;
    private final PyclawRuntimeProperties properties;

    public RouteBindingRuntimeController(RouteBindingService service, PyclawRuntimeProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/runtime")
    public List<RouteBindingResponse> runtime(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        requireInternalToken(authorization);
        return service.runtimeList();
    }

    private void requireInternalToken(String authorization) {
        String expected = properties.internalToken();
        if (expected == null || expected.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Internal runtime config token is not configured");
        }
        String prefix = "Bearer ";
        if (authorization == null || !authorization.startsWith(prefix)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing internal API token");
        }
        String actual = authorization.substring(prefix.length());
        if (!expected.equals(actual)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid internal API token");
        }
    }
}
