package com.dadp.wrapper.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class WrapperLocalCryptoDebug {

    private WrapperLocalCryptoDebug() {
    }

    static String preview(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() > 48 ? value.substring(0, 48) + "..." : value;
    }

    static String fingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return toHex(hash, 12);
        } catch (Exception e) {
            return "sha256-error";
        }
    }

    private static String toHex(byte[] bytes, int maxChars) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
            if (sb.length() >= maxChars) {
                return sb.substring(0, maxChars);
            }
        }
        return sb.toString();
    }
}
