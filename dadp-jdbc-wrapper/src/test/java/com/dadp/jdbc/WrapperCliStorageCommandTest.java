package com.dadp.jdbc;

import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.policy.PolicyMappingStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WrapperCliStorageCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void commandSavesEnrollmentAndAppliesRefreshResponse() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int saveCode = WrapperCliStorageCommand.run(new String[] {
                "save-enrollment",
                "--storage-dir", tempDir.toString(),
                "--tenant-id", "wtenant_cli",
                "--alias", "A01",
                "--runtime-version", "1",
                "--runtime-hub-url", "http://dadp-hub:9004"
        }, new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, saveCode);
        assertTrue(out.toString("UTF-8").contains("\"saved\" : true"));

        String response = "{"
                + "\"runtimeVersion\":2,"
                + "\"wrapper\":{\"hubUrl\":\"http://dadp-hub:9004\",\"engineUrl\":\"http://dadp-engine:9003\",\"cryptoMode\":\"local\",\"failOpen\":false},"
                + "\"policyBindings\":[{\"schemaName\":\"public\",\"tableName\":\"users\",\"columnName\":\"email\",\"policyCode\":\"GMMAQB25\",\"status\":\"ACTIVE\"}]"
                + "}";
        Path responseFile = tempDir.resolve("refresh.json");
        Files.write(responseFile, response.getBytes(StandardCharsets.UTF_8));

        out.reset();
        int refreshCode = WrapperCliStorageCommand.run(new String[] {
                "apply-refresh-response",
                "--storage-dir", tempDir.toString(),
                "--response-file", responseFile.toString()
        }, new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, refreshCode);
        JsonNode result = new ObjectMapper().readTree(out.toString("UTF-8"));
        assertEquals(2L, result.path("runtimeVersion").asLong());
        assertEquals(1, result.path("mappingCount").asInt());

        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");
        assertEquals("local", configStorage.loadConfig(null, null).getCryptoMode());
        assertEquals("GMMAQB25", new PolicyMappingStorage(tempDir.toString(), "policy-mappings.json")
                .loadMappings()
                .get("public.users.email"));
    }

    @Test
    void commandRejectsInitialEnrollmentWithoutAlias() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int saveCode = WrapperCliStorageCommand.run(new String[] {
                "save-enrollment",
                "--storage-dir", tempDir.toString(),
                "--tenant-id", "wtenant_cli",
                "--runtime-version", "1"
        }, new PrintStream(out), new PrintStream(err));

        assertEquals(2, saveCode);
        JsonNode result = new ObjectMapper().readTree(out.toString("UTF-8"));
        assertEquals(false, result.path("saved").asBoolean(true));
    }

    @Test
    void commandKeepsExistingAliasWhenEnrollmentAliasIsOmitted() throws Exception {
        WrapperCliStorageSupport.saveEnrollment(tempDir.toString(), "wtenant_cli", "orders-db", "1", "http://hub:9004");

        int saveCode = WrapperCliStorageCommand.run(new String[] {
                "save-enrollment",
                "--storage-dir", tempDir.toString(),
                "--tenant-id", "wtenant_cli",
                "--runtime-version", "2",
                "--runtime-hub-url", "http://hub:9004"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, saveCode);
        JsonNode stored = new ObjectMapper().readTree(tempDir.resolve("proxy-config.json").toFile());
        assertEquals("orders-db", stored.path("alias").asText());
        assertEquals("2", stored.path("runtimeVersion").asText());
    }

    @Test
    void commandPersistedEnrollmentContainsAlias() throws Exception {
        int saveCode = WrapperCliStorageCommand.run(new String[] {
                "save-enrollment",
                "--storage-dir", tempDir.toString(),
                "--tenant-id", "wtenant_cli",
                "--alias", "orders-db",
                "--runtime-version", "1",
                "--runtime-hub-url", "http://dadp-hub:9004"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, saveCode);
        JsonNode stored = new ObjectMapper().readTree(tempDir.resolve("proxy-config.json").toFile());
        assertEquals("orders-db", stored.path("alias").asText());
        assertEquals("wtenant_cli", stored.path("tenantId").asText());
    }

    @Test
    void commandAppliesRefreshResponseWithCliHubUrlFallback() throws Exception {
        WrapperCliStorageSupport.saveEnrollment(tempDir.toString(), "wtenant_cli", "A01", "1", null);
        String response = "{"
                + "\"runtimeVersion\":2,"
                + "\"wrapper\":{\"engineUrl\":\"http://dadp-engine:9003\",\"cryptoMode\":\"remote\",\"failOpen\":false},"
                + "\"policyBindings\":[]"
                + "}";
        Path responseFile = tempDir.resolve("refresh-no-hub-url.json");
        Files.write(responseFile, response.getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int refreshCode = WrapperCliStorageCommand.run(new String[] {
                "apply-refresh-response",
                "--storage-dir", tempDir.toString(),
                "--response-file", responseFile.toString(),
                "--runtime-hub-url", "http://cli-hub:9004"
        }, new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, refreshCode);
        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");
        assertEquals("http://cli-hub:9004", configStorage.loadConfig(null, null).getRuntime().getHubUrl());
    }

    @Test
    void commandBuildsSchemaRegisterPayloadWithoutCliDtoReimplementation() throws Exception {
        WrapperCliStorageSupport.saveEnrollment(tempDir.toString(), "wtenant_existing", "A01", "1", "http://hub:9004");
        Path schemasJson = tempDir.resolve("schemas.json");
        Files.write(schemasJson, ("{"
                + "\"alias\":\"A01\","
                + "\"wrapperType\":\"JDBC\","
                + "\"datasource\":{},"
                + "\"schema\":{\"version\":1}"
                + "}").getBytes(StandardCharsets.UTF_8));
        Path output = tempDir.resolve("payload.json");

        int exitCode = WrapperCliStorageCommand.run(new String[] {
                "build-schema-register-payload",
                "--schemas-json", schemasJson.toString(),
                "--storage-dir", tempDir.toString(),
                "--app-name", "app",
                "--wrapper-version", "6.0.0",
                "--client-instance-id", "cli-1",
                "--output", output.toString()
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exitCode);
        JsonNode payload = new ObjectMapper().readTree(output.toFile());
        assertEquals("wtenant_existing", payload.path("tenantId").asText());
        assertEquals("A01", payload.path("alias").asText());
        assertEquals("app", payload.path("appName").asText());
    }

    @Test
    void commandResolvesRuntimeContextFromWrapperLibDir() throws Exception {
        Path libDir = tempDir.resolve("lib");
        Path storageDir = libDir.resolve("dadp").resolve("wrapper").resolve("A01");
        WrapperCliStorageSupport.saveEnrollment(storageDir.toString(), "wtenant_existing", "A01", "7", "http://hub:9004");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = WrapperCliStorageCommand.run(new String[] {
                "resolve-runtime-context",
                "--wrapper-lib-dir", libDir.toString()
        }, new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exitCode);
        JsonNode context = new ObjectMapper().readTree(out.toString("UTF-8"));
        assertEquals("wtenant_existing", context.path("tenantId").asText());
        assertEquals("A01", context.path("alias").asText());
        assertEquals("7", context.path("runtimeVersion").asText());
        assertEquals(storageDir.toString(), context.path("storageDir").asText());
    }

    @Test
    void commandRejectsMultipleRuntimeContexts() throws Exception {
        Path libDir = tempDir.resolve("lib");
        WrapperCliStorageSupport.saveEnrollment(
                libDir.resolve("dadp").resolve("wrapper").resolve("A01").toString(),
                "wtenant_one", "A01", "1", "http://hub:9004");
        WrapperCliStorageSupport.saveEnrollment(
                libDir.resolve("dadp").resolve("wrapper").resolve("A02").toString(),
                "wtenant_two", "A02", "1", "http://hub:9004");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = WrapperCliStorageCommand.run(new String[] {
                "resolve-runtime-context",
                "--wrapper-lib-dir", libDir.toString()
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));

        assertEquals(1, exitCode);
        assertTrue(err.toString("UTF-8").contains("Multiple wrapper runtime directories found"));
    }
}
