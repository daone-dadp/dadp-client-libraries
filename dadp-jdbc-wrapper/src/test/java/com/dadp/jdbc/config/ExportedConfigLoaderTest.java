package com.dadp.jdbc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.WrapperRuntimeConfigManager;
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
                + "  \"hubUrl\": \"http://must-not-be-used:9004\",\n"
                + "  \"mappings\": {\"users.email\":\"dadp\"},\n"
                + "  \"statsConfig\": {\"enabled\": true, \"url\": \"http://aggregator:9005\"}\n"
                + "}\n";
        Files.write(storageDir.resolve("exported-config.json"), json.getBytes(StandardCharsets.UTF_8));

        InstanceConfigStorage configStorage =
                new InstanceConfigStorage(storageDir.toString(), "instance-config.json");
        WrapperRuntimeConfigManager tenantIdManager =
                new WrapperRuntimeConfigManager(configStorage, "http://hub:9004", new InstanceIdProvider("wrapper-test"), null);
        PolicyResolver policyResolver = new PolicyResolver(storageDir.toString(), "policy-mappings.json");
        EndpointStorage endpointStorage = new EndpointStorage(storageDir.toString(), "crypto-endpoints.json");

        String loadedTenantId = ExportedConfigLoader.loadIfExists(
                storageDir.toString(),
                "wrapper-test",
                tenantIdManager,
                policyResolver,
                endpointStorage);

        assertEquals("wtenant_test", loadedTenantId);
        InstanceConfigStorage.ConfigData saved = configStorage.loadConfig("http://hub:9004", "wrapper-test");
        assertNotNull(saved);
        assertEquals("wtenant_test", saved.getTenantId());
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
                + "  \"cryptoUrl\": \"http://engine:9003\"\n"
                + "}\n";
        Files.write(storageDir.resolve("exported-config.json"), json.getBytes(StandardCharsets.UTF_8));

        InstanceConfigStorage configStorage =
                new InstanceConfigStorage(storageDir.toString(), "instance-config.json");
        WrapperRuntimeConfigManager tenantIdManager =
                new WrapperRuntimeConfigManager(configStorage, "http://hub:9004", new InstanceIdProvider("wrapper-test"), null);

        String loadedTenantId = ExportedConfigLoader.loadIfExists(
                storageDir.toString(),
                "wrapper-test",
                tenantIdManager,
                new PolicyResolver(storageDir.toString(), "policy-mappings.json"),
                new EndpointStorage(storageDir.toString(), "crypto-endpoints.json"));

        assertEquals(null, loadedTenantId);
        assertFalse(configStorage.hasStoredConfig());
    }

    @Test
    void emptyExportedMappingsDoNotOverwriteExistingLocalMappings() throws Exception {
        Path storageDir = tempDir.resolve("wrapper-empty-mapping");
        Files.createDirectories(storageDir);

        String json = "{\n"
                + "  \"exportVersion\": 6,\n"
                + "  \"tenantId\": \"wtenant_test\",\n"
                + "  \"mappings\": {}\n"
                + "}\n";
        Files.write(storageDir.resolve("exported-config.json"), json.getBytes(StandardCharsets.UTF_8));

        InstanceConfigStorage configStorage =
                new InstanceConfigStorage(storageDir.toString(), "instance-config.json");
        WrapperRuntimeConfigManager tenantIdManager =
                new WrapperRuntimeConfigManager(configStorage, "http://hub:9004", new InstanceIdProvider("wrapper-test"), null);
        PolicyResolver policyResolver = new PolicyResolver(storageDir.toString(), "policy-mappings.json");
        HashMap<String, String> existingMappings = new HashMap<>();
        existingMappings.put("users.email", "dadp");
        policyResolver.refreshMappings(existingMappings, 6L);

        String loadedTenantId = ExportedConfigLoader.loadIfExists(
                storageDir.toString(),
                "wrapper-test",
                tenantIdManager,
                policyResolver,
                new EndpointStorage(storageDir.toString(), "crypto-endpoints.json"));

        assertEquals("wtenant_test", loadedTenantId);
        assertEquals(Long.valueOf(6L), policyResolver.getCurrentVersion());
        assertEquals("dadp", policyResolver.resolvePolicy(null, null, "users", "email"));
    }

    @Test
    void refreshWithoutCryptoModeDoesNotResetStoredLocalModeToRemote() throws Exception {
        Path storageDir = tempDir.resolve("wrapper-runtime-options");
        Files.createDirectories(storageDir);

        InstanceConfigStorage configStorage =
                new InstanceConfigStorage(storageDir.toString(), "instance-config.json");
        WrapperRuntimeConfigManager manager =
                new WrapperRuntimeConfigManager(configStorage, "http://hub:9004", new InstanceIdProvider("wrapper-test"), null);

        assertEquals("remote", manager.getCryptoMode());
        assertEquals(false, manager.isFailOpen());

        manager.applyRefreshOptions(Boolean.TRUE, "local", Boolean.FALSE, Boolean.FALSE, "7", true);
        assertEquals("local", manager.getCryptoMode());
        assertEquals(false, manager.isFailOpen());

        manager.applyRefreshOptions(Boolean.TRUE, null, Boolean.FALSE, Boolean.FALSE, "8", true);
        assertEquals("local", manager.getCryptoMode());
        assertEquals(false, manager.isFailOpen());

        WrapperRuntimeConfigManager reloaded =
                new WrapperRuntimeConfigManager(configStorage, "http://hub:9004", new InstanceIdProvider("wrapper-test"), null);
        reloaded.loadFromStorage();
        assertEquals("local", reloaded.getCryptoMode());
        assertEquals(false, reloaded.isFailOpen());
    }
}
