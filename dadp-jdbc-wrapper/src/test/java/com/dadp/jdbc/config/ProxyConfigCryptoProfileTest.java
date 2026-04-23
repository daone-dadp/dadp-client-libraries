package com.dadp.jdbc.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProxyConfigCryptoProfileTest {

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("dadp.wrapper.crypto-profile.enabled");
        System.clearProperty("dadp.wrapper.crypto-profile.path");
    }

    @Test
    void cryptoProfileDefaultsToDisabled() {
        ProxyConfig config = new ProxyConfig(Collections.emptyMap());

        assertFalse(config.isCryptoProfileEnabled());
        assertTrue(config.getCryptoProfilePath().endsWith("crypto-stage-profile.ndjson"));
    }

    @Test
    void cryptoProfileCanBeEnabledFromJdbcUrlParams() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("instanceId", "wrapper-profile-test");
        urlParams.put("cryptoProfileEnabled", "true");
        urlParams.put("cryptoProfilePath", "/tmp/dadp/wrapper-profile.ndjson");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertTrue(config.isCryptoProfileEnabled());
        assertTrue("/tmp/dadp/wrapper-profile.ndjson".equals(config.getCryptoProfilePath()));
    }
}
