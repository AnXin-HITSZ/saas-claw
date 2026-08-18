package com.saasclaw.backend.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文件内容摘要工具。统一走 JDK MessageDigest，避免额外引入 commons-codec。
 * 与 {@link ApiKeyUtil#hash(String)} 保持同一 SHA-256 + 十六进制小写实现。
 */
public class HashUtil {

    private HashUtil() {
    }

    /** 计算字节内容的 SHA-256 十六进制摘要（小写）。 */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
