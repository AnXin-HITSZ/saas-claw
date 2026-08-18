package com.saasclaw.gateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT 解析（只解析不签发，签发在 backend）。
 *
 * 与 backend JwtUtil 保持同一 key（jwt.secret）与同一载荷约定：subject = userId。
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey key;

    public JwtUtil(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析 JWT，返回 userId；无效 / 过期 / 载荷非法返回 null。
     */
    public Long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException | NumberFormatException e) {
            log.warn("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }
}