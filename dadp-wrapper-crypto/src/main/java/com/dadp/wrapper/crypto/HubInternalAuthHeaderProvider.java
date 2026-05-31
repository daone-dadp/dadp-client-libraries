package com.dadp.wrapper.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

/**
 * Creates DADP 6.0.0 internal auth headers for Hub runtime APIs.
 */
public final class HubInternalAuthHeaderProvider implements HubAuthHeaderProvider {

    private final String callerKey;
    private final String sharedSecret;
    private final String tenantId;
    private final TimeProvider timeProvider;
    private final NonceProvider nonceProvider;

    public HubInternalAuthHeaderProvider(String callerKey, String sharedSecret) {
        this(null, callerKey, sharedSecret, new SystemTimeProvider(), new UuidNonceProvider());
    }

    public HubInternalAuthHeaderProvider(String tenantId, String callerKey, String sharedSecret) {
        this(tenantId, callerKey, sharedSecret, new SystemTimeProvider(), new UuidNonceProvider());
    }

    HubInternalAuthHeaderProvider(String callerKey, String sharedSecret,
                                  TimeProvider timeProvider, NonceProvider nonceProvider) {
        this(null, callerKey, sharedSecret, timeProvider, nonceProvider);
    }

    HubInternalAuthHeaderProvider(String tenantId, String callerKey, String sharedSecret,
                                  TimeProvider timeProvider, NonceProvider nonceProvider) {
        if (callerKey == null || callerKey.trim().isEmpty()) {
            throw new IllegalArgumentException("callerKey is required");
        }
        if (sharedSecret == null || sharedSecret.trim().isEmpty()) {
            throw new IllegalArgumentException("sharedSecret is required");
        }
        this.tenantId = tenantId != null && !tenantId.trim().isEmpty() ? tenantId.trim() : null;
        this.callerKey = callerKey.trim();
        this.sharedSecret = sharedSecret.trim();
        this.timeProvider = timeProvider;
        this.nonceProvider = nonceProvider;
    }

    @Override
    public void applyAuthHeaders(HttpURLConnection connection, String method, String path, String query, byte[] body) {
        String timestamp = timeProvider.now();
        String nonce = nonceProvider.nonce();
        String bodyHash = sha256Hex(body != null ? body : new byte[0]);
        String canonical = canonicalString(method, path, query, timestamp, nonce, bodyHash, callerKey);
        String signature = hmacSha256Hex(sharedSecret, canonical);

        connection.setRequestProperty("X-Hub-Auth-Key", callerKey);
        connection.setRequestProperty("X-Hub-Auth-Timestamp", timestamp);
        connection.setRequestProperty("X-Hub-Auth-Nonce", nonce);
        connection.setRequestProperty("X-Hub-Auth-Signature", signature);
        connection.setRequestProperty("X-Hub-Auth-Version", "v1");
        if (tenantId != null) {
            connection.setRequestProperty("X-DADP-Tenant-Id", tenantId);
        }
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
            throw new WrapperCryptoException("SHA-256 body hash failed: " + e.getMessage(), e);
        }
    }

    static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new WrapperCryptoException("Hub internal auth signature creation failed: " + e.getMessage(), e);
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

    interface TimeProvider {
        String now();
    }

    interface NonceProvider {
        String nonce();
    }

    private static final class SystemTimeProvider implements TimeProvider {
        @Override
        public String now() {
            return Instant.now().toString();
        }
    }

    private static final class UuidNonceProvider implements NonceProvider {
        @Override
        public String nonce() {
            return UUID.randomUUID().toString();
        }
    }
}
