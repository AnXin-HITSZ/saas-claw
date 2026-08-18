package com.saasclaw.gateway.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * API Key 工具：只做哈希比对。哈希算法与 backend ApiKeyUtil 完全一致（SHA-256 hex），
 * 才能等值查询 authorization 表。
 */
public final class ApiKeyUtil {

    private ApiKeyUtil() {
    }

    public static String hash(String apiKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}