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
        System.clearProperty("dadp.wrapper.single-transport-mode");
        System.clearProperty("dadp.wrapper.engine-transport");
        System.clearProperty("dadp.wrapper.engine-binary-port");
    }

    @Test
    void cryptoProfileDefaultsToDisabled() {
        ProxyConfig config = new ProxyConfig(Collections.emptyMap());

        assertFalse(config.isCryptoProfileEnabled());
        assertTrue("json".equals(config.getSingleTransportMode()));
        assertTrue("http".equals(config.getEngineTransport()));
        assertTrue(config.getEngineBinaryPort() == 9104);
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

    @Test
    void singleTransportModeCanBeEnabledFromJdbcUrlParams() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("singleTransportMode", "binary-framed");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertTrue("binary-framed".equals(config.getSingleTransportMode()));
    }

    @Test
    void engineTransportCanBeEnabledFromJdbcUrlParams() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("engineTransport", "binary-tcp");
        urlParams.put("engineBinaryPort", "19104");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertTrue("binary-tcp".equals(config.getEngineTransport()));
        assertTrue(config.getEngineBinaryPort() == 19104);
    }
}
