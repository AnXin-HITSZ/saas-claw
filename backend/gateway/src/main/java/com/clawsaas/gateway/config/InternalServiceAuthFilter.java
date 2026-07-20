package com.clawsaas.gateway.config;

import com.clawsaas.gateway.auth.AuthenticatedPrincipal;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Validates internal service-to-service calls.
 *
 * FastAPI must send {@code Authorization: Bearer <internal-token>} where
 * the token matches {@code gateway.runtime.internal-token}.
 *
 * This principal is tagged {@code INTERNAL_SERVICE} — it is NOT a user JWT
 * and must never be treated as a user login session.
 */
public class InternalServiceAuthFilter implements WebFilter {

    private static final String INTERNAL_PATH_PREFIX = "/api/internal/";
    private static final String ACTOR_TYPE_INTERNAL = "INTERNAL_SERVICE";

    private final String internalToken;

    public InternalServiceAuthFilter(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith(INTERNAL_PATH_PREFIX)) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authorization.substring("Bearer ".length());
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "internal", "internal-service", ACTOR_TYPE_INTERNAL,
                List.of(new SimpleGrantedAuthority("agent:run"), new SimpleGrantedAuthority("agent:read"))
        );
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }
}
