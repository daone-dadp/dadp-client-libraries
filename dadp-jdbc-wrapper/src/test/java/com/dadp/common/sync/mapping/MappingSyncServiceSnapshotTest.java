package com.dadp.common.sync.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.dadp.common.sync.policy.PolicyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappingSyncServiceSnapshotTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void deserializesEngineEndpointFromPolicySnapshotEndpoint() throws Exception {
        String json = "{"
                + "\"version\":17,"
                + "\"mappings\":[],"
                + "\"endpoint\":{"
                + "\"cryptoUrl\":\"http://engine:9003\","
                + "\"apiBasePath\":\"/api\""
                + "}"
                + "}";

        MappingSyncService.PolicySnapshot snapshot =
                objectMapper.readValue(json, MappingSyncService.PolicySnapshot.class);

        assertNotNull(snapshot);
        assertNotNull(snapshot.getEndpoint());
        assertEquals("http://engine:9003", snapshot.getEndpoint().getCryptoUrl());
    }

    @Test
    void runtimeRefreshParsesCryptoModeAndEngineEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hub/api/v1/runtime/wrappers/wtenant_test/refresh", exchange -> {
            assertEquals("wtenant_test", exchange.getRequestHeaders().getFirst("X-DADP-Tenant-Id"));
            assertEquals("version=6", exchange.getRequestURI().getQuery());
            byte[] body = ("{"
                    + "\"wrapper\":{\"enabled\":true,\"debugEnabled\":true,\"debugLevel\":\"TRACE\","
                    + "\"cryptoMode\":\"local\",\"policySyncAutoEnabled\":true,\"failOpen\":true,"
                    + "\"engineUrl\":\"http://engine:9003\"},"
                    + "\"runtimeVersion\":7,"
	                    + "\"policyBindings\":[{"
	                    + "\"schemaName\":\"public\","
	                    + "\"tableName\":\"users\","
	                    + "\"columnName\":\"email\","
	                    + "\"policyCode\":\"PVTNFPQQ\","
	                    + "\"deterministic\":true,"
	                    + "\"status\":\"ACTIVE\""
	                    + "},{"
	                    + "\"schemaName\":\"public\","
	                    + "\"tableName\":\"users\","
	                    + "\"columnName\":\"phone\","
	                    + "\"policyCode\":\"PARTIAL01\","
	                    + "\"partialEncryption\":true,"
	                    + "\"plainStart\":0,"
	                    + "\"plainLength\":3,"
	                    + "\"status\":\"ACTIVE\""
	                    + "}]"
	                    + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String hubUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            PolicyResolver resolver = new PolicyResolver(tempDir.toString(), "policy-mappings.json");
            MappingSyncService service = new MappingSyncService(
                    hubUrl,
                    "wtenant_test",
                    "alias-test",
                    "/hub/api/v1/runtime/wrappers",
                    resolver);

            int count = service.syncPolicyMappingsAndUpdateVersion(6L);

	            assertEquals(2, count);
	            assertNotNull(service.getLastSnapshot());
            assertNotNull(service.getLastSnapshot().getWrapperConfig());
            assertEquals(Boolean.TRUE, service.getLastSnapshot().getWrapperConfig().getEnabled());
            assertEquals("local", service.getLastSnapshot().getWrapperConfig().getCryptoMode());
            assertEquals(Boolean.TRUE, service.getLastSnapshot().getWrapperConfig().getPolicySyncAutoEnabled());
            assertEquals(Boolean.TRUE, service.getLastSnapshot().getWrapperConfig().getFailOpen());
            assertNotNull(service.getLastSnapshot().getLogConfig());
            assertEquals(Boolean.TRUE, service.getLastSnapshot().getLogConfig().getEnabled());
            assertEquals("TRACE", service.getLastSnapshot().getLogConfig().getLevel());
	            assertNotNull(service.getLastSnapshot().getEndpoint());
	            assertEquals("http://engine:9003", service.getLastSnapshot().getEndpoint().getCryptoUrl());
	            assertEquals("PVTNFPQQ", resolver.resolvePolicy(null, "public", "users", "email"));
	            assertEquals("PARTIAL01", resolver.resolvePolicy(null, "public", "users", "phone"));
	            assertEquals(true, resolver.isSearchEncryptionNeeded("PVTNFPQQ"));
	            assertEquals(false, resolver.isSearchEncryptionNeeded("PARTIAL01"));
	        } finally {
	            server.stop(0);
	        }
    }

    @Test
    void runtimeRefreshTreatsNotModifiedAsNoop() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hub/api/v1/runtime/wrappers/wtenant_test/refresh", exchange -> {
            assertEquals("wtenant_test", exchange.getRequestHeaders().getFirst("X-DADP-Tenant-Id"));
            assertEquals("version=7", exchange.getRequestURI().getQuery());
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
        });
        server.start();
        try {
            String hubUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            PolicyResolver resolver = new PolicyResolver(tempDir.toString(), "policy-mappings.json");
            MappingSyncService service = new MappingSyncService(
                    hubUrl,
                    "wtenant_test",
                    "alias-test",
                    "/hub/api/v1/runtime/wrappers",
                    resolver);

            int count = service.syncPolicyMappingsAndUpdateVersion(7L);

            assertEquals(0, count);
            assertEquals(null, service.getLastSnapshot());
            assertEquals(Long.valueOf(0L), resolver.getCurrentVersion());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runtimeRefreshTreatsUnauthorizedAsNoop() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hub/api/v1/runtime/wrappers/wtenant_test/refresh", exchange -> {
            assertEquals("wtenant_test", exchange.getRequestHeaders().getFirst("X-DADP-Tenant-Id"));
            assertEquals(null, exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            String hubUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            PolicyResolver resolver = new PolicyResolver(tempDir.toString(), "policy-mappings.json");
            MappingSyncService service = new MappingSyncService(
                    hubUrl,
                    "wtenant_test",
                    "alias-test",
                    "/hub/api/v1/runtime/wrappers",
                    resolver);

            int count = service.syncPolicyMappingsAndUpdateVersion(7L);

            assertEquals(0, count);
            assertEquals(null, service.getLastSnapshot());
            assertEquals(Long.valueOf(0L), resolver.getCurrentVersion());
        } finally {
            server.stop(0);
        }
    }
}
