package com.dadp.common.sync.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Creates DADP 6.0 Hub runtime internal-auth headers.
 */
public final class HubInternalAuthSigner {

    private final String callerKey;
    private final String sharedSecret;

    public HubInternalAuthSigner(String callerKey, String sharedSecret) {
        if (callerKey == null || callerKey.trim().isEmpty()) {
            throw new IllegalArgumentException("callerKey is required");
        }
        if (sharedSecret == null || sharedSecret.trim().isEmpty()) {
            throw new IllegalArgumentException("sharedSecret is required");
        }
        this.callerKey = callerKey.trim();
        this.sharedSecret = sharedSecret.trim();
    }

    public Map<String, String> sign(String method, URI uri, byte[] body) {
        String timestamp = Instant.now().toString();
        String nonce = UUID.randomUUID().toString();
        String bodyHash = sha256Hex(body != null ? body : new byte[0]);
        String canonical = canonicalString(method, uri.getRawPath(), uri.getRawQuery(),
                timestamp, nonce, bodyHash, callerKey);
        String signature = hmacSha256Hex(sharedSecret, canonical);

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Hub-Auth-Key", callerKey);
        headers.put("X-Hub-Auth-Timestamp", timestamp);
        headers.put("X-Hub-Auth-Nonce", nonce);
        headers.put("X-Hub-Auth-Signature", signature);
        headers.put("X-Hub-Auth-Version", "v1");
        return headers;
    }

    static String canonicalString(String method, String path, String query,
                                  String timestamp, String nonce, String bodySha256, String callerKey) {
        return trimUpper(method) + "\n"
                + trim(path) + "\n"
                + trim(query) + "\n"
                + trim(timestamp) + "\n"
                + trim(nonce) + "\n"
                + trim(bodySha256) + "\n"
                + trim(callerKey);
    }

    static String sha256Hex(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(body));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 body hash failed: " + e.getMessage(), e);
        }
    }

    static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Hub internal auth signature creation failed: " + e.getMessage(), e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String value = Integer.toHexString(b & 0xff);
            if (value.length() == 1) {
                sb.append('0');
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private static String trimUpper(String value) {
        return trim(value).toUpperCase();
    }

    private static String trim(String value) {
        return value != null ? value.trim() : "";
    }
}
