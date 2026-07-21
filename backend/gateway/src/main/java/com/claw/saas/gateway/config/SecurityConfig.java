package com.claw.saas.gateway.config;

import com.claw.saas.gateway.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                   BearerAuthenticationFilter bearerFilter) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/webhooks/wechat",
                                "/api/webhooks/feishu",
                                "/api/internal/route-bindings/**",
                                "/actuator/health",
                                "/healthz"
                        ).permitAll()
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .anyExchange().authenticated()
                )
                .addFilterBefore(bearerFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    BearerAuthenticationFilter bearerAuthenticationFilter(JwtService jwtService, UserRepository users) {
        return new BearerAuthenticationFilter(jwtService, users);
    }
}
