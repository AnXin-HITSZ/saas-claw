package com.clawsaas.gateway.config;

import com.clawsaas.gateway.auth.AuthUtil;
import com.clawsaas.gateway.auth.AuthenticatedPrincipal;
import com.clawsaas.gateway.entity.UserEntity;
import com.clawsaas.gateway.repository.UserRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Authenticates requests via Bearer JWT tokens.
 * This is a WebFlux filter — unlike the old MVC OncePerRequestFilter,
 * it uses {@link ReactiveSecurityContextHolder} to set the security context.
 */
public class BearerAuthenticationFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserRepository users;

    public BearerAuthenticationFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return chain.filter(exchange);
        }

        String token = authorization.substring("Bearer ".length());
        return Mono.fromCallable(() -> authenticateJwt(token))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(principal -> {
                    if (principal != null) {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                        return chain.filter(exchange)
                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(e -> {
                    log.debug("Bearer authentication failed: {}", e.getMessage());
                    return chain.filter(exchange);
                });
    }

    private AuthenticatedPrincipal authenticateJwt(String token) {
        Map<String, Object> payload = jwtService.verify(token);
        String userId = String.valueOf(payload.get("sub"));
        UserEntity user = users.findById(userId)
                .filter(value -> "ACTIVE".equals(value.getStatus()))
                .orElseThrow();
        return new AuthenticatedPrincipal(
                user.getId(),
                user.getUsername(),
                "USER",
                AuthUtil.authorities(user.getAuthorities())
        );
    }
}
