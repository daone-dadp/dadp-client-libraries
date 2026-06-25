package com.dadp.jdbc.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dadp.common.sync.config.StoragePathResolver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProxyConfigCryptoProfileTest {

    @AfterEach
    void clearSystemProperties() throws Exception {
        System.clearProperty("dadp.proxy.alias");
        System.clearProperty("dadp.proxy.hub-url");
        System.clearProperty("dadp.wrapper.crypto-profile.enabled");
        System.clearProperty("dadp.wrapper.crypto-profile.path");
        deleteRuntimeRoot();
    }

    @Test
    void cryptoProfileDefaultsToDisabled() {
        ProxyConfig config = new ProxyConfig(baseParams());

        assertFalse(config.isCryptoProfileEnabled());
        assertTrue("json".equals(config.getSingleTransportMode()));
        assertTrue("http".equals(config.getEngineTransport()));
        assertTrue(config.getEngineBinaryPort() == 9104);
        assertFalse(config.isFailOpen());
        assertTrue("remote".equals(config.getCryptoMode()));
        assertTrue(config.isCryptoLocalFallbackRemote());
        assertTrue(config.getCryptoLocalTimeoutMs() == 30000);
        assertFalse(config.isWrapperCryptoStatsEnabled());
        assertTrue("1hour".equals(config.getWrapperCryptoStatsAggregationLevel()));
        assertFalse(config.isSqlMappingDebugEnabled());
        assertFalse(config.isAutoPolicyMappingSyncEnabled());
        assertTrue(config.getCryptoProfilePath().endsWith("crypto-stage-profile.ndjson"));
    }

    @Test
    void cryptoProfileCannotBeEnabledFromJdbcUrlParams() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("alias", "wrapper-profile-test");
        urlParams.put("hubUrl", "http://127.0.0.1:9004");
        urlParams.put("cryptoProfileEnabled", "true");
        urlParams.put("cryptoProfilePath", "/tmp/dadp/wrapper-profile.ndjson");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertFalse(config.isCryptoProfileEnabled());
        assertTrue(config.getCryptoProfilePath().endsWith("crypto-stage-profile.ndjson"));
    }

    @Test
    void missingAliasDisablesWrapperAndLegacyInstanceIdDoesNotWork() {
        ProxyConfig missingAliasConfig = new ProxyConfig(Collections.emptyMap());
        assertFalse(missingAliasConfig.isStartupReady());
        assertFalse(missingAliasConfig.isRuntimeActive());
        assertTrue(missingAliasConfig.isEnabled());

        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("instanceId", "legacy-instance-id");
        urlParams.put("hubUrl", "http://127.0.0.1:9004");
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
            assertTrue(output.contains("proxy-config.json"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void runtimeIdentityLoadsFromProxyConfig() throws Exception {
        writeProxyConfig("stored-alias",
                "wtenant_test",
                "http://dadp-hub:9004");

        ProxyConfig config = new ProxyConfig(Collections.emptyMap());

        assertTrue(config.isStartupReady());
        assertTrue(config.isRuntimeActive());
        assertTrue("stored-alias".equals(config.getAlias()));
        assertTrue("stored-alias".equals(config.getInstanceId()));
        assertTrue("http://dadp-hub:9004".equals(config.getHubUrl()));
        assertTrue(config.isHubUrlConfigured());
    }

    @Test
    void runtimeOptionsLoadFromProxyConfigBeforeBootstrap() throws Exception {
        writeProxyConfig("stored-alias",
                "wtenant_test",
                "http://dadp-hub:9004",
                false,
                true,
                "local");

        ProxyConfig config = new ProxyConfig(Collections.emptyMap());

        assertFalse(config.isEnabled());
        assertFalse(config.isRuntimeActive());
        assertTrue(config.isFailOpen());
        assertTrue("local".equals(config.getCryptoMode()));
    }

    @Test
    void aliasCannotBeLoadedFromSystemProperty() {
        System.setProperty("dadp.proxy.alias", "system-prop-alias");

        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("hubUrl", "http://127.0.0.1:9004");
        ProxyConfig config = new ProxyConfig(urlParams);

        assertFalse(config.isStartupReady());
        assertFalse(config.isRuntimeActive());
    }

    @Test
    void hubUrlCannotBeLoadedFromSystemPropertyOrEnvFallback() {
        System.setProperty("dadp.proxy.hub-url", "http://system-hub:9004");

        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("alias", "jdbc-url-alias");
        ProxyConfig config = new ProxyConfig(urlParams);

        assertFalse(config.isStartupReady());
        assertFalse(config.isRuntimeActive());
        assertFalse(config.isHubUrlConfigured());
    }

    @Test
    void runtimeTransportCannotBeEnabledFromJdbcUrlParams() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("alias", "shared-db-group");
        urlParams.put("hubUrl", "http://127.0.0.1:9004");
        urlParams.put("singleTransportMode", "binary-framed");
        urlParams.put("engineTransport", "binary-tcp");
        urlParams.put("engineBinaryPort", "19104");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertTrue("json".equals(config.getSingleTransportMode()));
        assertTrue("http".equals(config.getEngineTransport()));
        assertTrue(config.getEngineBinaryPort() == 9104);
    }

    @Test
    void localCryptoModeCannotBeEnabledFromJdbcUrlParams() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("alias", "shared-db-group");
        urlParams.put("hubUrl", "http://127.0.0.1:9004");
        urlParams.put("cryptoMode", "local");
        urlParams.put("cryptoLocalFallbackRemote", "false");
        urlParams.put("cryptoLocalTimeoutMs", "1234");
        urlParams.put("wrapperCryptoStatsEnabled", "true");
        urlParams.put("wrapperCryptoStatsAggregationLevel", "1day");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertTrue("remote".equals(config.getCryptoMode()));
        assertTrue(config.isCryptoLocalFallbackRemote());
        assertTrue(config.getCryptoLocalTimeoutMs() == 30000);
        assertFalse(config.isWrapperCryptoStatsEnabled());
        assertTrue("1hour".equals(config.getWrapperCryptoStatsAggregationLevel()));
    }

    @Test
    void sqlMappingDebugCannotBeEnabledFromJdbcUrlParams() {
        Map<String, String> urlParams = new HashMap<>();
        urlParams.put("alias", "shared-db-group");
        urlParams.put("hubUrl", "http://127.0.0.1:9004");
        urlParams.put("sqlMappingDebugEnabled", "true");

        ProxyConfig config = new ProxyConfig(urlParams);

        assertFalse(config.isSqlMappingDebugEnabled());
    }

    @Test
    void autoPolicyMappingSyncCannotBeEnabledFromJdbcUrlParams() {
        Map<String, String> defaultParams = new HashMap<>();
        defaultParams.put("alias", "shared-db-group");
        defaultParams.put("hubUrl", "http://127.0.0.1:9004");
        ProxyConfig defaultConfig = new ProxyConfig(defaultParams);
        assertFalse(defaultConfig.isAutoPolicyMappingSyncEnabled());

        Map<String, String> enabledParams = new HashMap<>();
        enabledParams.put("alias", "shared-db-group");
        enabledParams.put("hubUrl", "http://127.0.0.1:9004");
        enabledParams.put("policySyncAutoEnabled", "true");
        ProxyConfig enabledConfig = new ProxyConfig(enabledParams);
        assertFalse(enabledConfig.isAutoPolicyMappingSyncEnabled());
    }

    private static Map<String, String> baseParams() {
        Map<String, String> params = new HashMap<>();
        params.put("hubUrl", "http://127.0.0.1:9004");
        params.put("alias", "wrapper-profile-test");
        return params;
    }

    private static void writeProxyConfig(String alias, String tenantId, String runtimeHubUrl) throws IOException {
        writeProxyConfig(alias, tenantId, runtimeHubUrl, true, false, "remote");
    }

    private static void writeProxyConfig(String alias,
                                         String tenantId,
                                         String runtimeHubUrl,
                                         boolean enabled,
                                         boolean failOpen,
                                         String cryptoMode) throws IOException {
        deleteRuntimeRoot();
        Path storageDir = Paths.get(StoragePathResolver.resolveStorageDir(alias));
        Files.createDirectories(storageDir);
        String json = "{\n"
                + "  \"tenantId\": \"" + tenantId + "\",\n"
                + "  \"alias\": \"" + alias + "\",\n"
                + "  \"runtimeVersion\": \"1\",\n"
                + "  \"runtime\": {\n"
                + "    \"hubUrl\": \"" + runtimeHubUrl + "\",\n"
                + "    \"enabled\": " + enabled + ",\n"
                + "    \"failOpen\": " + failOpen + ",\n"
                + "    \"cryptoMode\": \"" + cryptoMode + "\"\n"
                + "  }\n"
                + "}\n";
        Files.write(storageDir.resolve("proxy-config.json"), json.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRuntimeRoot() throws IOException {
        Path root = Paths.get(StoragePathResolver.resolveWrapperStorageRoot());
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}
