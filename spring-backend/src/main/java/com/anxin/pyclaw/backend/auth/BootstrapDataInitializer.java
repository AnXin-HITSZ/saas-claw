package com.anxin.pyclaw.backend.auth;

import com.anxin.pyclaw.backend.config.PyclawSecurityProperties;
import com.anxin.pyclaw.backend.user.UserEntity;
import com.anxin.pyclaw.backend.user.UserRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapDataInitializer implements CommandLineRunner {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PyclawSecurityProperties properties;

    public BootstrapDataInitializer(UserRepository users, PasswordEncoder passwordEncoder, PyclawSecurityProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        users.findByUsername(properties.bootstrapAdminUsername()).ifPresentOrElse(user -> {
            String merged = mergeAuthorities(user.getAuthorities(), adminAuthorities());
            if (!merged.equals(user.getAuthorities())) {
                user.setAuthorities(merged);
                user.setUpdatedAt(OffsetDateTime.now());
                users.save(user);
            }
        }, () -> {
            OffsetDateTime now = OffsetDateTime.now();
            UserEntity admin = new UserEntity();
            admin.setId(UUID.randomUUID().toString());
            admin.setUsername(properties.bootstrapAdminUsername());
            admin.setDisplayName("Administrator");
            admin.setPasswordHash(passwordEncoder.encode(properties.bootstrapAdminPassword()));
            admin.setStatus("ACTIVE");
            admin.setAuthorities(adminAuthorities());
            admin.setCreatedAt(now);
            admin.setUpdatedAt(now);
            users.save(admin);
        });
    }

    private String adminAuthorities() {
        return "user:manage,provider:manage,channel:manage,agent:read,agent:create,agent:update,agent:delete,agent:route:manage,agent:run,tool:catalog:read,tool:grant:minimal,tool:grant:readonly,tool:grant:messaging,tool:grant:coding,tool:grant:full,tool:grant:shell,tool:grant:web,tool:grant:host,audit:read,approval:resolve,token:manage_self";
    }

    private String mergeAuthorities(String current, String required) {
        Set<String> values = new LinkedHashSet<>();
        for (String source : new String[] { current, required }) {
            if (source == null || source.isBlank()) {
                continue;
            }
            for (String item : source.split(",")) {
                String authority = item.trim();
                if (!authority.isBlank()) {
                    values.add(authority);
                }
            }
        }
        return String.join(",", values);
    }
}
