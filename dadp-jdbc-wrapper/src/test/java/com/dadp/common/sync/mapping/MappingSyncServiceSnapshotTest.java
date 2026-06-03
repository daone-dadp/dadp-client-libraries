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
    void deserializesStatsAggregatorFromPolicySnapshotEndpoint() throws Exception {
        String json = "{"
                + "\"version\":17,"
                + "\"mappings\":[],"
                + "\"endpoint\":{"
                + "\"cryptoUrl\":\"http://engine:9003\","
                + "\"apiBasePath\":\"/api\","
                + "\"statsAggregator\":{"
                + "\"enabled\":true,"
                + "\"url\":\"http://aggregator:9005\","
                + "\"mode\":\"DIRECT\","
                + "\"slowThresholdMs\":321"
                + "}"
                + "}"
                + "}";

        MappingSyncService.PolicySnapshot snapshot =
                objectMapper.readValue(json, MappingSyncService.PolicySnapshot.class);

        assertNotNull(snapshot);
        assertNotNull(snapshot.getEndpoint());
        assertEquals("http://engine:9003", snapshot.getEndpoint().getCryptoUrl());
        assertNotNull(snapshot.getEndpoint().getStatsAggregator());
        assertEquals(Boolean.TRUE, snapshot.getEndpoint().getStatsAggregator().getEnabled());
        assertEquals("http://aggregator:9005", snapshot.getEndpoint().getStatsAggregator().getUrl());
        assertEquals("DIRECT", snapshot.getEndpoint().getStatsAggregator().getMode());
        assertEquals(Integer.valueOf(321), snapshot.getEndpoint().getStatsAggregator().getSlowThresholdMs());
    }

    @Test
    void runtimeRefreshParsesCryptoModeAndEngineEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hub/api/v1/runtime/wrappers/wtenant_test/refresh", exchange -> {
            assertEquals("wtenant_test", exchange.getRequestHeaders().getFirst("X-DADP-Tenant-Id"));
            byte[] body = ("{"
                    + "\"wrapper\":{\"enabled\":true,\"cryptoMode\":\"local\"},"
                    + "\"engine\":{\"wrapperEngineUrl\":\"http://engine:9003\"},"
                    + "\"runtimeVersion\":7,"
                    + "\"policyBindings\":[{"
                    + "\"sharedDatasourceId\":\"ds-test\","
                    + "\"schemaName\":\"public\","
                    + "\"tableName\":\"users\","
                    + "\"columnName\":\"email\","
                    + "\"policyCode\":\"PVTNFPQQ\","
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
                    "ds-test",
                    "/hub/api/v1/runtime/wrappers",
                    resolver);

            int count = service.syncPolicyMappingsAndUpdateVersion(null);

            assertEquals(1, count);
            assertNotNull(service.getLastSnapshot());
            assertNotNull(service.getLastSnapshot().getWrapperConfig());
            assertEquals(Boolean.TRUE, service.getLastSnapshot().getWrapperConfig().getEnabled());
            assertEquals("local", service.getLastSnapshot().getWrapperConfig().getCryptoMode());
            assertNotNull(service.getLastSnapshot().getEndpoint());
            assertEquals("http://engine:9003", service.getLastSnapshot().getEndpoint().getCryptoUrl());
            assertEquals("PVTNFPQQ", resolver.resolvePolicy(null, "public", "users", "email"));
        } finally {
            server.stop(0);
        }
    }
}
