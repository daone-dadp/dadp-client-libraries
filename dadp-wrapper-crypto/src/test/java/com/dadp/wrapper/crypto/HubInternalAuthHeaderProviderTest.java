package com.dadp.wrapper.crypto;

import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HubInternalAuthHeaderProviderTest {

    @Test
    void createsDadp60InternalAuthHeaders() throws Exception {
        HubInternalAuthHeaderProvider provider = new HubInternalAuthHeaderProvider(
                "wrapper-key",
                "wrapper-secret",
                () -> "2026-05-28T00:00:00Z",
                () -> "nonce-1");
        byte[] body = "{\"purpose\":\"WRAPPER_LOCAL\"}".getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL("http://localhost/hub/api/v1/runtime/execution-keys/resolve").openConnection();

        provider.applyAuthHeaders(connection, "POST", "/hub/api/v1/runtime/execution-keys/resolve", "", body);

        String bodyHash = HubInternalAuthHeaderProvider.sha256Hex(body);
        String canonical = HubInternalAuthHeaderProvider.canonicalString(
                "POST",
                "/hub/api/v1/runtime/execution-keys/resolve",
                "",
                "2026-05-28T00:00:00Z",
                "nonce-1",
                bodyHash,
                "wrapper-key");
        assertEquals("wrapper-key", connection.getRequestProperty("X-Hub-Auth-Key"));
        assertEquals("2026-05-28T00:00:00Z", connection.getRequestProperty("X-Hub-Auth-Timestamp"));
        assertEquals("nonce-1", connection.getRequestProperty("X-Hub-Auth-Nonce"));
        assertEquals("v1", connection.getRequestProperty("X-Hub-Auth-Version"));
        assertEquals(
                HubInternalAuthHeaderProvider.hmacSha256Hex("wrapper-secret", canonical),
                connection.getRequestProperty("X-Hub-Auth-Signature"));
    }
}
