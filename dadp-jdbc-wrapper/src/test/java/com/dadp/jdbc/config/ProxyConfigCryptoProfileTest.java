package com.dadp.jdbc.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ProxyConfigCryptoProfileTest {

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("dadp.proxy.alias");
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
        System.clearProperty("dadp.wrapper.crypto-stats.enabled");
        System.clearProperty("dadp.wrapper.crypto-stats.aggregation-level");
    }

    @Test
    void cryptoProfileDefaultsToDisabled() {
        ProxyConfig config = new ProxyConfig(Collections.singletonMap("alias", "wrapper-profile-test"));

        assertFalse(config.isCryptoProfileEnabled());
        assertTrue("json".equals(config.getSingleTransportMode()));
        assertTrue("http".equals(config.getEngineTransport()));
        assertTrue(config.getEngineBinaryPort() == 9104);
        assertTrue("remote".equals(config.getCryptoMode()));
        assertTrue(config.isCryptoLocalFallbackRemote());
        assertTrue(config.getCryptoLocalTimeoutMs() == 30000);
        assertFalse(config.isWrapperCryptoStatsEnabled());
        assertTrue("1hour".equals(config.getWrapperCryptoStatsAggregationLevel()));
        assertTrue(config.getCryptoProfilePath().endsWith("crypto-stage-profile.ndjson"));
    }

    @Test
    void cryptoProfileCanBeEnabledFromJdbcUrlParams() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("alias", "wrapper-profile-test");
        urlParams.put("cryptoProfileEnabled", "true");
        urlParams.put("cryptoProfilePath", "/tmp/dadp/wrapper-profile.ndjson");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertTrue(config.isCryptoProfileEnabled());
        assertTrue("/tmp/dadp/wrapper-profile.ndjson".equals(config.getCryptoProfilePath()));
    }

    @Test
    void missingAliasDisablesWrapperAndLegacyInstanceIdDoesNotWork() {
        ProxyConfig missingAliasConfig = new ProxyConfig(Collections.emptyMap());
        assertFalse(missingAliasConfig.isStartupReady());
        assertFalse(missingAliasConfig.isRuntimeActive());
        assertTrue(missingAliasConfig.isEnabled());

        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("instanceId", "legacy-instance-id");
        ProxyConfig legacyInstanceIdOnlyConfig = new ProxyConfig(urlParams);
        assertFalse(legacyInstanceIdOnlyConfig.isStartupReady());
        assertFalse(legacyInstanceIdOnlyConfig.isRuntimeActive());
        assertTrue(legacyInstanceIdOnlyConfig.isEnabled());
    }

    @Test
    void missingAliasAlwaysPrintsFailureMessageToStderr() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(stderr, true));

            ProxyConfig config = new ProxyConfig(Collections.emptyMap());
            String output = stderr.toString();
            assertFalse(config.isStartupReady());
            assertTrue(output.contains("missing required alias"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void aliasCanBeLoadedFromJdbcUrlParamOnly() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("alias", "jdbc-url-alias");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertTrue("jdbc-url-alias".equals(config.getAlias()));
        assertTrue("jdbc-url-alias".equals(config.getInstanceId()));
    }

    @Test
    void aliasCanBeLoadedFromSystemPropertyOnly() {
        System.setProperty("dadp.proxy.alias", "system-prop-alias");

        ProxyConfig config = new ProxyConfig(Collections.emptyMap());

        assertTrue("system-prop-alias".equals(config.getAlias()));
        assertTrue("system-prop-alias".equals(config.getInstanceId()));
    }

    @Test
    void aliasCanBeLoadedFromEnvironmentOnly() {
        Assumptions.assumeTrue("env-alias-only".equals(System.getenv("DADP_PROXY_ALIAS")));
        ProxyConfig config = new ProxyConfig(Collections.emptyMap());

        assertTrue("env-alias-only".equals(config.getAlias()));
        assertTrue("env-alias-only".equals(config.getInstanceId()));
    }

    @Test
    void singleTransportModeCanBeEnabledFromJdbcUrlParams() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("alias", "shared-db-group");
        urlParams.put("singleTransportMode", "binary-framed");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertTrue("binary-framed".equals(config.getSingleTransportMode()));
    }

    @Test
    void engineTransportCanBeEnabledFromJdbcUrlParams() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("alias", "shared-db-group");
        urlParams.put("engineTransport", "binary-tcp");
        urlParams.put("engineBinaryPort", "19104");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertTrue("binary-tcp".equals(config.getEngineTransport()));
        assertTrue(config.getEngineBinaryPort() == 19104);
    }

    @Test
    void localCryptoModeCanBeEnabledFromJdbcUrlParams() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("alias", "shared-db-group");
        urlParams.put("cryptoMode", "local");
        urlParams.put("cryptoLocalFallbackRemote", "false");
        urlParams.put("cryptoLocalTimeoutMs", "1234");
        urlParams.put("cryptoLocalHubAuthId", "hub-default");
        urlParams.put("cryptoLocalHubAuthSecret", "secret");
        urlParams.put("wrapperCryptoStatsEnabled", "true");
        urlParams.put("wrapperCryptoStatsAggregationLevel", "1day");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertTrue("local".equals(config.getCryptoMode()));
        assertFalse(config.isCryptoLocalFallbackRemote());
        assertTrue(config.getCryptoLocalTimeoutMs() == 1234);
        assertTrue("hub-default".equals(config.getCryptoLocalHubAuthId()));
        assertTrue("secret".equals(config.getCryptoLocalHubAuthSecret()));
        assertTrue(config.isWrapperCryptoStatsEnabled());
        assertTrue("1day".equals(config.getWrapperCryptoStatsAggregationLevel()));
    }
}
