package com.anxin.pyclaw.backend.config;

import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates internal service-to-service calls from FastAPI to Spring.
 *
 * FastAPI must send {@code Authorization: Bearer <internal-token>} where
 * the token matches {@code pyclaw.runtime.internal-token}.
 *
 * This principal is tagged {@code INTERNAL_SERVICE} — it is NOT a user JWT
 * and must never be treated as a user login session.
 */
public class InternalServiceAuthFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PATH_PREFIX = "/api/internal/";
    private static final String ACTOR_TYPE_INTERNAL = "INTERNAL_SERVICE";

    private final String internalToken;

    public InternalServiceAuthFilter(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(INTERNAL_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Internal service token required");
            return;
        }

        String token = authorization.substring("Bearer ".length());
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid internal service token");
            return;
        }

        // Set internal service principal — NOT a user principal
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "internal", "internal-service", ACTOR_TYPE_INTERNAL,
                List.of(new SimpleGrantedAuthority("agent:run"), new SimpleGrantedAuthority("agent:read"))
        );
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }
}
