package com.clawsaas.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class AuthUtil {
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthUtil() {
    }

    public static String randomToken(String prefix, int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception exc) {
            throw new IllegalStateException("SHA-256 is unavailable", exc);
        }
    }

    public static List<GrantedAuthority> authorities(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Set.of(csv.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
