package com.dadp.wrapper.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HubInternalKeyClientTest {

    @Test
    void buildsEngineCompatibleHubApiUrl() {
        HubInternalKeyClient client = new HubInternalKeyClient("http://hub:9004/hub/ignored", 1000, null);

        assertEquals(
                "http://hub:9004/hub/api/v1/keys/internal/key/1",
                client.buildHubApiUrl("/api/v1/keys/internal/key/1"));
    }
}
