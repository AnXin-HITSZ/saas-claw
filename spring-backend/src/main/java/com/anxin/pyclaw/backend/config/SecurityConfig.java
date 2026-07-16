package com.anxin.pyclaw.backend.config;

import com.anxin.pyclaw.backend.auth.AuthUtil;
import com.anxin.pyclaw.backend.auth.AuthenticatedPrincipal;
import com.anxin.pyclaw.backend.auth.JwtService;
import com.anxin.pyclaw.backend.token.ApiTokenEntity;
import com.anxin.pyclaw.backend.token.ApiTokenService;
import com.anxin.pyclaw.backend.user.UserEntity;
import com.anxin.pyclaw.backend.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BearerAuthenticationFilter bearerFilter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/webhooks/wechat",
                                "/api/webhooks/feishu",
                                "/api/internal/channels/**",
                                "/api/internal/agents/**",
                                "/api/internal/route-bindings/**",
                                "/actuator/health",
                                "/healthz"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    BearerAuthenticationFilter bearerAuthenticationFilter(JwtService jwtService, ApiTokenService apiTokens, UserRepository users) {
        return new BearerAuthenticationFilter(jwtService, apiTokens, users);
    }

    static class BearerAuthenticationFilter extends OncePerRequestFilter {
        private final JwtService jwtService;
        private final ApiTokenService apiTokens;
        private final UserRepository users;

        BearerAuthenticationFilter(JwtService jwtService, ApiTokenService apiTokens, UserRepository users) {
            this.jwtService = jwtService;
            this.apiTokens = apiTokens;
            this.users = users;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.startsWith("Bearer ")) {
                String token = authorization.substring("Bearer ".length());
                try {
                    if (token.startsWith("pcat_")) {
                        authenticateApiToken(token);
                    } else {
                        authenticateJwt(token);
                    }
                } catch (RuntimeException ignored) {
                    SecurityContextHolder.clearContext();
                }
            }
            filterChain.doFilter(request, response);
        }

        private void authenticateJwt(String token) {
            Map<String, Object> payload = jwtService.verify(token);
            String userId = String.valueOf(payload.get("sub"));
            UserEntity user = users.findById(userId)
                    .filter(value -> "ACTIVE".equals(value.getStatus()))
                    .orElseThrow();
            setAuthentication(new AuthenticatedPrincipal(
                    user.getId(),
                    user.getUsername(),
                    "USER",
                    AuthUtil.authorities(user.getAuthorities())
            ));
        }

        private void authenticateApiToken(String token) {
            ApiTokenEntity entity = apiTokens.requireActiveToken(token);
            UserEntity user = users.findById(entity.getUserId()).orElseThrow();
            setAuthentication(new AuthenticatedPrincipal(user.getId(), user.getUsername(), "API_TOKEN", AuthUtil.authorities(entity.getScopes())));
        }

        private void setAuthentication(AuthenticatedPrincipal principal) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    principal.getAuthorities()
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
    }
}
