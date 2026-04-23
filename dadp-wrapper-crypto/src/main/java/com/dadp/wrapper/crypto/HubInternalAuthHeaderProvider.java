package com.dadp.wrapper.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Creates Engine-compatible X-Hub-Auth headers for Hub internal APIs.
 */
public final class HubInternalAuthHeaderProvider implements HubInternalKeyClient.AuthHeaderProvider {

    private final String hubId;
    private final String hubAuthSecret;

    public HubInternalAuthHeaderProvider(String hubId, String hubAuthSecret) {
        if (hubId == null || hubId.trim().isEmpty()) {
            throw new IllegalArgumentException("hubId is required");
        }
        if (hubAuthSecret == null || hubAuthSecret.trim().isEmpty()) {
            throw new IllegalArgumentException("hubAuthSecret is required");
        }
        this.hubId = hubId.trim();
        this.hubAuthSecret = hubAuthSecret.trim();
    }

    @Override
    public String createAuthHeader(String engineStylePath) {
        long timestamp = System.currentTimeMillis();
        String payload = hubId + "|" + timestamp + "|" + engineStylePath;
        return hubId + ":" + timestamp + ":" + hmacSha256(payload);
    }

    private String hmacSha256(String payload) {
        try {
            byte[] secretBytes = Base64.getDecoder().decode(hubAuthSecret);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new WrapperCryptoException("Hub internal auth header creation failed: " + e.getMessage(), e);
        }
    }
}
