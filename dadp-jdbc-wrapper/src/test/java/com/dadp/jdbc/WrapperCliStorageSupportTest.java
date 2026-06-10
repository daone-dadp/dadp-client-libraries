package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.policy.PolicyMappingStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class WrapperCliStorageSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesStorageDirFromWrapperLibDirAndAlias() {
        assertEquals(
                tempDir.resolve("lib").resolve("dadp").resolve("wrapper").resolve("A01").toString(),
                WrapperCliStorageSupport.resolveStorageDir(tempDir.resolve("lib").toString(), "A01"));
    }

    @Test
    void schemaRegisterPayloadReusesExistingTenantIdFromProxyConfig() throws Exception {
        WrapperCliStorageSupport.saveEnrollment(tempDir.toString(), "wtenant_existing", "7");
        assertEquals("7", WrapperCliStorageSupport.loadRuntimeVersion(tempDir.toString()));

        Map<String, Object> schemaCache = new LinkedHashMap<>();
        schemaCache.put("alias", "A01");
        schemaCache.put("wrapperType", "JDBC");
        schemaCache.put("datasource", new LinkedHashMap<String, Object>());
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("version", 1);
        schemaCache.put("schema", schema);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        File schemasJson = tempDir.resolve("schemas.json").toFile();
        mapper.writeValue(schemasJson, schemaCache);

        Map<String, Object> payload = WrapperCliStorageSupport.buildSchemaRegisterPayload(
                schemasJson,
                tempDir.toString(),
                "app",
                "6.0.0",
                "client-1");

        assertEquals("wtenant_existing", payload.get("tenantId"));
        assertEquals("A01", payload.get("alias"));
        assertEquals("app", payload.get("appName"));
    }

    @Test
    void refreshResponsePersistsProxyConfigAndPolicyMappings() throws Exception {
        WrapperCliStorageSupport.saveEnrollment(tempDir.toString(), "wtenant_existing", "7");

        String response = "{"
                + "\"runtimeVersion\":8,"
                + "\"wrapper\":{\"hubUrl\":\"http://dadp-hub:9004\",\"cryptoMode\":\"local\",\"failOpen\":false,\"policySyncAutoEnabled\":true},"
                + "\"runtime\":{\"engineEndpointUrl\":\"http://dadp-engine:9003\"},"
                + "\"policyBindings\":[{"
                + "\"schemaName\":\"public\","
                + "\"tableName\":\"users\","
                + "\"columnName\":\"email\","
                + "\"policyCode\":\"PVTNFPQQ\","
                + "\"status\":\"ACTIVE\","
                + "\"deterministic\":true,"
                + "\"partialEncryption\":false"
                + "}]"
                + "}";

        WrapperCliStorageSupport.RefreshApplyResult result =
                WrapperCliStorageSupport.applyRefreshResponse(tempDir.toString(), response);

        assertEquals(Long.valueOf(8L), result.getRuntimeVersion());
        assertEquals(1, result.getMappingCount());
        assertEquals("http://dadp-engine:9003", result.getWrapperEngineUrl());

        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");
        InstanceConfigStorage.ConfigData config = configStorage.loadConfig(null, null);
        assertNotNull(config);
        assertEquals("wtenant_existing", config.getTenantId());
        assertEquals("8", config.getRuntimeVersion());
        assertEquals("local", config.getCryptoMode());
        assertEquals(Boolean.FALSE, config.getFailOpen());
        assertEquals(Boolean.TRUE, config.getPolicySyncAutoEnabled());
        assertEquals("http://dadp-hub:9004", config.getRuntime().getHubUrl());
        assertEquals("http://dadp-hub:9004/hub/api/v1/runtime/wrappers/wtenant_existing/refresh",
                config.getRuntime().getRefreshUrl());
        assertEquals("http://dadp-hub:9004/hub/api/v1/runtime/wrappers/wtenant_existing/schema-sync",
                config.getRuntime().getSchemaSyncUrl());
        assertEquals("http://dadp-engine:9003", config.getRuntime().getEngineEndpointUrl());
        assertEquals("http://dadp-engine:9003", configStorage.loadEndpointData().getCryptoUrl());

        PolicyMappingStorage mappingStorage = new PolicyMappingStorage(tempDir.toString(), "policy-mappings.json");
        assertEquals(Long.valueOf(8L), mappingStorage.loadVersion());
        assertEquals("PVTNFPQQ", mappingStorage.loadMappings().get("public.users.email"));
        assertEquals(Boolean.FALSE, mappingStorage.loadPolicyAttributes().get("PVTNFPQQ").getUseIv());
        assertEquals(Boolean.FALSE, mappingStorage.loadPolicyAttributes().get("PVTNFPQQ").getUsePlain());
    }

    @Test
    void runtimeOptionSavePreservesCliFieldsAndDoesNotWriteNullBootstrapFields() throws Exception {
        String proxyConfig = "{"
                + "\"alias\":\"dadp-test-app-standalone-mysql\","
                + "\"tenantId\":\"wtenant_existing\","
                + "\"runtimeVersion\":\"4\","
                + "\"schemaVersion\":4,"
                + "\"snapshotVersion\":4,"
                + "\"runtime\":{"
                + "\"refreshUrl\":\"/hub/api/v1/runtime/wrappers/wtenant_existing/refresh\","
                + "\"schemaSyncUrl\":\"/hub/api/v1/runtime/wrappers/wtenant_existing/schema-sync\""
                + "},"
                + "\"hubUrl\":null,"
                + "\"instanceId\":null"
                + "}";
        Files.write(tempDir.resolve("proxy-config.json"), proxyConfig.getBytes(StandardCharsets.UTF_8));

        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");
        configStorage.saveRuntimeOptions("remote", Boolean.FALSE, Boolean.TRUE, "8", "http://dadp-engine:9003");

        String stored = new String(Files.readAllBytes(tempDir.resolve("proxy-config.json")), StandardCharsets.UTF_8);
        JsonNode json = new ObjectMapper().readTree(stored);

        assertEquals("dadp-test-app-standalone-mysql", json.path("alias").asText());
        assertEquals("wtenant_existing", json.path("tenantId").asText());
        assertEquals("8", json.path("runtimeVersion").asText());
        assertEquals(4, json.path("schemaVersion").asInt());
        assertEquals(8, json.path("snapshotVersion").asInt());
        assertEquals("/hub/api/v1/runtime/wrappers/wtenant_existing/refresh",
                json.path("runtime").path("refreshUrl").asText());
        assertEquals("http://dadp-engine:9003", json.path("engine").path("wrapperEngineUrl").asText());
        assertEquals("http://dadp-engine:9003", json.path("runtime").path("engineEndpointUrl").asText());
        assertTrue(json.path("policySyncAutoEnabled").asBoolean(false));
        assertFalse(json.has("hubUrl"));
        assertFalse(json.has("instanceId"));
        assertFalse(stored.contains("\"hubUrl\""));
        assertFalse(stored.contains("\"instanceId\""));
    }

    @Test
    void runtimeOptionSaveWritesTenantIdProvidedByRuntimeWhenFileLostIt() throws Exception {
        String proxyConfig = "{"
                + "\"runtimeVersion\":\"4\","
                + "\"hubUrl\":null,"
                + "\"instanceId\":null"
                + "}";
        Files.write(tempDir.resolve("proxy-config.json"), proxyConfig.getBytes(StandardCharsets.UTF_8));

        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");
        configStorage.saveRuntimeOptions("remote", Boolean.FALSE, Boolean.TRUE, "9", "http://dadp-engine:9003", "wtenant_runtime");

        JsonNode json = new ObjectMapper().readTree(tempDir.resolve("proxy-config.json").toFile());
        assertEquals("wtenant_runtime", json.path("tenantId").asText());
        assertEquals("9", json.path("runtimeVersion").asText());
        assertFalse(json.has("hubUrl"));
        assertFalse(json.has("instanceId"));
    }
}
