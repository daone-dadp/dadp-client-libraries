package com.dadp.jdbc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.HubIdManager;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.config.InstanceIdProvider;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.jdbc.logging.DadpLoggerFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportedConfigLoaderTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetLoggerFactoryState() throws Exception {
        setStaticField("hubManaged", false);
        setStaticField("loggingEnabled", false);
        setStaticField("minimumLogLevel", "INFO");
    }

    @Test
    void prefersStatsConfigAndFallsBackToLegacyKeys() throws Exception {
        Path storageDir = tempDir.resolve("wrapper");
        Files.createDirectories(storageDir);

        String json = "{\n"
                + "  \"exportVersion\": 1,\n"
                + "  \"hubId\": \"hub-test\",\n"
                + "  \"instanceId\": \"wrapper-test\",\n"
                + "  \"datasourceId\": \"ds-test\",\n"
                + "  \"cryptoUrl\": \"http://engine:9003\",\n"
                + "  \"hubUrl\": \"http://hub:9004\",\n"
                + "  \"policyVersion\": 9,\n"
                + "  \"mappings\": {},\n"
                + "  \"statsConfig\": {\n"
                + "    \"enabled\": true,\n"
                + "    \"url\": \"http://aggregator-new:9005\",\n"
                + "    \"mode\": \"DIRECT\",\n"
                + "    \"slowThresholdMs\": 432\n"
                + "  },\n"
                + "  \"statsAggregatorEnabled\": false,\n"
                + "  \"statsAggregatorUrl\": \"http://aggregator-old:9005\",\n"
                + "  \"statsAggregatorMode\": \"GATEWAY\",\n"
                + "  \"statsAggregatorSlowThresholdMs\": 999\n"
                + "}\n";
        Files.write(storageDir.resolve("exported-config.json"), json.getBytes(StandardCharsets.UTF_8));

        InstanceConfigStorage configStorage =
                new InstanceConfigStorage(storageDir.toString(), "instance-config.json");
        HubIdManager hubIdManager =
                new HubIdManager(configStorage, "http://hub:9004", new InstanceIdProvider("wrapper-test"), null);
        PolicyResolver policyResolver = new PolicyResolver(storageDir.toString(), "policy-mappings.json");
        EndpointStorage endpointStorage = new EndpointStorage(storageDir.toString(), "crypto-endpoints.json");

        String datasourceId = ExportedConfigLoader.loadIfExists(
                storageDir.toString(),
                "wrapper-test",
                hubIdManager,
                policyResolver,
                endpointStorage);

        assertEquals("ds-test", datasourceId);
        EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
        assertNotNull(endpointData);
        assertEquals(Boolean.TRUE, endpointData.getStatsAggregatorEnabled());
        assertEquals("http://aggregator-new:9005", endpointData.getStatsAggregatorUrl());
        assertEquals("DIRECT", endpointData.getStatsAggregatorMode());
        assertEquals(Integer.valueOf(432), endpointData.getSlowThresholdMs());
    }

    @Test
    void appliesLogConfigAndReenablesWrapperFromExportedConfig() throws Exception {
        Path storageDir = tempDir.resolve("wrapper-log");
        Files.createDirectories(storageDir);

        String json = "{\n"
                + "  \"exportVersion\": 1,\n"
                + "  \"hubId\": \"hub-test\",\n"
                + "  \"instanceId\": \"wrapper-test\",\n"
                + "  \"datasourceId\": \"ds-test\",\n"
                + "  \"cryptoUrl\": \"http://engine:9003\",\n"
                + "  \"policyVersion\": 9,\n"
                + "  \"mappings\": {},\n"
                + "  \"wrapperConfig\": {\n"
                + "    \"enabled\": true\n"
                + "  },\n"
                + "  \"logConfig\": {\n"
                + "    \"enabled\": true,\n"
                + "    \"level\": \"DEBUG\"\n"
                + "  }\n"
                + "}\n";
        Files.write(storageDir.resolve("exported-config.json"), json.getBytes(StandardCharsets.UTF_8));

        InstanceConfigStorage configStorage =
                new InstanceConfigStorage(storageDir.toString(), "instance-config.json");
        HubIdManager hubIdManager =
                new HubIdManager(configStorage, "http://hub:9004", new InstanceIdProvider("wrapper-test"), null);
        PolicyResolver policyResolver = new PolicyResolver(storageDir.toString(), "policy-mappings.json");
        EndpointStorage endpointStorage = new EndpointStorage(storageDir.toString(), "crypto-endpoints.json");
        ProxyConfig proxyConfig = new ProxyConfig(Collections.singletonMap("enabled", "false"));

        assertFalse(proxyConfig.isEnabled());

        String datasourceId = ExportedConfigLoader.loadIfExists(
                storageDir.toString(),
                "wrapper-test",
                hubIdManager,
                policyResolver,
                endpointStorage,
                proxyConfig);

        assertEquals("ds-test", datasourceId);
        assertTrue(proxyConfig.isEnabled());
        assertTrue(DadpLoggerFactory.isHubManaged());
        assertTrue(DadpLoggerFactory.isLoggingEnabled());
        assertEquals("DEBUG", DadpLoggerFactory.getLogLevel());
        assertNotNull(policyResolver.getStoredLogConfig());
        assertEquals(Boolean.TRUE, policyResolver.getStoredLogConfig().getEnabled());
        assertEquals("DEBUG", policyResolver.getStoredLogConfig().getLevel());
    }

    private void setStaticField(String fieldName, Object value) throws Exception {
        Field field = DadpLoggerFactory.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}
