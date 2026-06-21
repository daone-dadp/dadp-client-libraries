package com.dadp.common.sync.config;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WrapperRuntimeConfigManagerTest {

    @Test
    void loadFromStorageUsesLegacyTopLevelHubUrlWhenRuntimeHubUrlIsMissing() throws Exception {
        Path storageDir = Files.createTempDirectory("dadp-wrapper-runtime-");
        Files.write(storageDir.resolve("proxy-config.json"),
                ("{\n"
                        + "  \"tenantId\": \"wtenant_local\",\n"
                        + "  \"alias\": \"customer-app\",\n"
                        + "  \"hubUrl\": \"http://dadp-hub:9004\",\n"
                        + "  \"runtimeVersion\": \"7\",\n"
                        + "  \"runtime\": {\n"
                        + "    \"cryptoMode\": \"local\",\n"
                        + "    \"engineUrl\": \"http://dadp-engine:9003\"\n"
                        + "  }\n"
                        + "}\n").getBytes(StandardCharsets.UTF_8));

        WrapperRuntimeConfigManager manager = new WrapperRuntimeConfigManager(
                new InstanceConfigStorage(storageDir.toString(), "proxy-config.json"),
                "http://localhost:9004",
                new InstanceIdProvider(java.util.Collections.singletonMap("alias", "customer-app")),
                null);

        assertEquals("wtenant_local", manager.loadFromStorage());
        assertEquals("local", manager.getCryptoMode());
        assertEquals("http://dadp-hub:9004", manager.getRuntimeHubUrl());
    }

    @Test
    void runtimeOptionSavePreservesExistingEngineUrlWhenRefreshOptionsOmitIt() throws Exception {
        Path storageDir = Files.createTempDirectory("dadp-wrapper-runtime-");
        Files.write(storageDir.resolve("proxy-config.json"),
                ("{\n"
                        + "  \"tenantId\": \"wtenant_local\",\n"
                        + "  \"alias\": \"customer-app\",\n"
                        + "  \"runtimeVersion\": \"7\",\n"
                        + "  \"runtime\": {\n"
                        + "    \"hubUrl\": \"http://dadp-hub:9004\",\n"
                        + "    \"engineUrl\": \"http://dadp-engine:9003\",\n"
                        + "    \"cryptoMode\": \"remote\"\n"
                        + "  }\n"
                        + "}\n").getBytes(StandardCharsets.UTF_8));

        InstanceConfigStorage storage = new InstanceConfigStorage(storageDir.toString(), "proxy-config.json");
        storage.saveRuntimeOptions("local", Boolean.FALSE, Boolean.TRUE, "8");

        JsonNode json = new ObjectMapper().readTree(storageDir.resolve("proxy-config.json").toFile());
        assertEquals("8", json.path("runtimeVersion").asText());
        assertEquals("local", json.path("runtime").path("cryptoMode").asText());
        assertEquals("http://dadp-engine:9003", json.path("runtime").path("engineUrl").asText());
    }
}
