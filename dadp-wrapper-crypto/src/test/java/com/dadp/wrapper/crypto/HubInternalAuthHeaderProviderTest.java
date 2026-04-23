package com.dadp.wrapper.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HubInternalAuthHeaderProviderTest {

    @Test
    void createsEngineCompatibleHeaderShape() {
        String secret = Base64.getEncoder().encodeToString(new byte[32]);
        HubInternalAuthHeaderProvider provider = new HubInternalAuthHeaderProvider("hub-default", secret);

        String header = provider.createAuthHeader("/api/v1/keys/internal/key/1");
        String[] parts = header.split(":");

        assertEquals(3, parts.length);
        assertEquals("hub-default", parts[0]);
    }
}
