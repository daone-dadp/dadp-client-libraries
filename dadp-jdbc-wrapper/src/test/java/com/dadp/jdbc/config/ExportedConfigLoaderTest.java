package com.dadp.jdbc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.TenantIdManager;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.config.InstanceIdProvider;
import com.dadp.common.sync.policy.PolicyResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportedConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsHub6EnrollmentOnlyFromExportedConfig() throws Exception {
        Path storageDir = tempDir.resolve("wrapper-enrollment");
        Files.createDirectories(storageDir);

        String json = "{\n"
                + "  \"exportVersion\": 6,\n"
                + "  \"tenantId\": \"wtenant_test\",\n"
                + "  \"datasourceId\": \"ds-test\",\n"
                + "  \"runtime\": {\n"
                + "    \"refreshUrl\": \"/hub/api/v1/runtime/wrappers/wtenant_test/refresh\",\n"
                + "    \"schemaSyncUrl\": \"/hub/api/v1/runtime/wrappers/wtenant_test/schema-sync\"\n"
                + "  },\n"
                + "  \"wrapperAuthSecret\": \"must-be-ignored\",\n"
                + "  \"hubUrl\": \"http://must-not-be-used:9004\",\n"
                + "  \"mappings\": {\"users.email\":\"dadp\"},\n"
                + "  \"statsConfig\": {\"enabled\": true, \"url\": \"http://aggregator:9005\"}\n"
                + "}\n";
        Files.write(storageDir.resolve("exported-config.json"), json.getBytes(StandardCharsets.UTF_8));

        InstanceConfigStorage configStorage =
                new InstanceConfigStorage(storageDir.toString(), "instance-config.json");
        TenantIdManager tenantIdManager =
                new TenantIdManager(configStorage, "http://hub:9004", new InstanceIdProvider("wrapper-test"), null);
        PolicyResolver policyResolver = new PolicyResolver(storageDir.toString(), "policy-mappings.json");
        EndpointStorage endpointStorage = new EndpointStorage(storageDir.toString(), "crypto-endpoints.json");

        String datasourceId = ExportedConfigLoader.loadIfExists(
                storageDir.toString(),
                "wrapper-test",
                tenantIdManager,
                policyResolver,
                endpointStorage);

        assertEquals("ds-test", datasourceId);
        InstanceConfigStorage.ConfigData saved = configStorage.loadConfig("http://hub:9004", "wrapper-test");
        assertNotNull(saved);
        assertEquals("wtenant_test", saved.getTenantId());
        assertEquals("ds-test", saved.getDatasourceId());
        assertEquals("/hub/api/v1/runtime/wrappers/wtenant_test/refresh", saved.getRefreshUrl());
        assertEquals("/hub/api/v1/runtime/wrappers/wtenant_test/schema-sync", saved.getSchemaSyncUrl());
        assertEquals(null, endpointStorage.loadEndpoints());
        assertEquals(Long.valueOf(0L), policyResolver.getCurrentVersion());
    }

    @Test
    void rejectsLegacyExportedConfig() throws Exception {
        Path storageDir = tempDir.resolve("wrapper-legacy-export");
        Files.createDirectories(storageDir);

        String json = "{\n"
                + "  \"exportVersion\": 1,\n"
                + "  \"tenantId\": \"legacy-hub\",\n"
                + "  \"instanceId\": \"wrapper-test\",\n"
                + "  \"datasourceId\": \"ds-test\",\n"
                + "  \"cryptoUrl\": \"http://engine:9003\"\n"
                + "}\n";
        Files.write(storageDir.resolve("exported-config.json"), json.getBytes(StandardCharsets.UTF_8));

        InstanceConfigStorage configStorage =
                new InstanceConfigStorage(storageDir.toString(), "instance-config.json");
        TenantIdManager tenantIdManager =
                new TenantIdManager(configStorage, "http://hub:9004", new InstanceIdProvider("wrapper-test"), null);

        String datasourceId = ExportedConfigLoader.loadIfExists(
                storageDir.toString(),
                "wrapper-test",
                tenantIdManager,
                new PolicyResolver(storageDir.toString(), "policy-mappings.json"),
                new EndpointStorage(storageDir.toString(), "crypto-endpoints.json"));

        assertEquals(null, datasourceId);
        assertFalse(configStorage.hasStoredConfig());
    }

    @Test
    void emptyExportedMappingsDoNotOverwriteExistingLocalMappings() throws Exception {
        Path storageDir = tempDir.resolve("wrapper-empty-mapping");
        Files.createDirectories(storageDir);

        String json = "{\n"
                + "  \"exportVersion\": 6,\n"
                + "  \"tenantId\": \"wtenant_test\",\n"
                + "  \"datasourceId\": \"ds-test\",\n"
                + "  \"runtime\": {\n"
                + "    \"refreshUrl\": \"/hub/api/v1/runtime/wrappers/wtenant_test/refresh\",\n"
                + "    \"schemaSyncUrl\": \"/hub/api/v1/runtime/wrappers/wtenant_test/schema-sync\"\n"
                + "  },\n"
                + "  \"mappings\": {}\n"
                + "}\n";
        Files.write(storageDir.resolve("exported-config.json"), json.getBytes(StandardCharsets.UTF_8));

        InstanceConfigStorage configStorage =
                new InstanceConfigStorage(storageDir.toString(), "instance-config.json");
        TenantIdManager tenantIdManager =
                new TenantIdManager(configStorage, "http://hub:9004", new InstanceIdProvider("wrapper-test"), null);
        PolicyResolver policyResolver = new PolicyResolver(storageDir.toString(), "policy-mappings.json");
        HashMap<String, String> existingMappings = new HashMap<>();
        existingMappings.put("users.email", "dadp");
        policyResolver.refreshMappings(existingMappings, 6L);

        String datasourceId = ExportedConfigLoader.loadIfExists(
                storageDir.toString(),
                "wrapper-test",
                tenantIdManager,
                policyResolver,
                new EndpointStorage(storageDir.toString(), "crypto-endpoints.json"));

        assertEquals("ds-test", datasourceId);
        assertEquals(Long.valueOf(6L), policyResolver.getCurrentVersion());
        assertEquals("dadp", policyResolver.resolvePolicy(null, null, "users", "email"));
    }
}
