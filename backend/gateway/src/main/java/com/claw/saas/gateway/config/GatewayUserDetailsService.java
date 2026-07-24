package com.claw.saas.gateway.config;

import com.claw.saas.gateway.auth.AuthUtil;
import com.claw.saas.gateway.entity.UserEntity;
import com.claw.saas.gateway.repository.UserRepository;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Custom {@link ReactiveUserDetailsService} backed by {@link UserRepository}.
 * Replaces Spring Boot's auto-configured {@code JdbcUserDetailsManager}
 * which would require the Spring Security standard users/authorities tables.
 */
@Component
public class GatewayUserDetailsService implements ReactiveUserDetailsService {

    private final UserRepository users;

    public GatewayUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public Mono<UserDetails> findByUsername(String username) {
        return Mono.fromCallable(() -> users.findByUsername(username)
                        .filter(u -> "ACTIVE".equals(u.getStatus()))
                        .map(this::toUserDetails)
                        .orElse(null))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private UserDetails toUserDetails(UserEntity entity) {
        return User.builder()
                .username(entity.getUsername())
                .password(entity.getPasswordHash())
                .authorities(AuthUtil.authorities(entity.getAuthorities()))
                .build();
    }
}
