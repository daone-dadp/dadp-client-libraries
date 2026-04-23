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
        System.clearProperty("dadp.wrapper.crypto-mode");
        System.clearProperty("dadp.wrapper.crypto-local.fallback-remote");
        System.clearProperty("dadp.wrapper.crypto-local.timeout-ms");
        System.clearProperty("dadp.wrapper.crypto-local.hub-auth-id");
        System.clearProperty("dadp.wrapper.crypto-local.hub-auth-secret");
    }

    @Test
    void cryptoProfileDefaultsToDisabled() {
        ProxyConfig config = new ProxyConfig(Collections.emptyMap());

        assertFalse(config.isCryptoProfileEnabled());
        assertTrue("json".equals(config.getSingleTransportMode()));
        assertTrue("http".equals(config.getEngineTransport()));
        assertTrue(config.getEngineBinaryPort() == 9104);
        assertTrue("remote".equals(config.getCryptoMode()));
        assertTrue(config.isCryptoLocalFallbackRemote());
        assertTrue(config.getCryptoLocalTimeoutMs() == 30000);
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

    @Test
    void localCryptoModeCanBeEnabledFromJdbcUrlParams() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("cryptoMode", "local");
        urlParams.put("cryptoLocalFallbackRemote", "false");
        urlParams.put("cryptoLocalTimeoutMs", "1234");
        urlParams.put("cryptoLocalHubAuthId", "hub-default");
        urlParams.put("cryptoLocalHubAuthSecret", "secret");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertTrue("local".equals(config.getCryptoMode()));
        assertFalse(config.isCryptoLocalFallbackRemote());
        assertTrue(config.getCryptoLocalTimeoutMs() == 1234);
        assertTrue("hub-default".equals(config.getCryptoLocalHubAuthId()));
        assertTrue("secret".equals(config.getCryptoLocalHubAuthSecret()));
    }
}
