package com.dadp.jdbc.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dadp.common.sync.config.EndpointStorage;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TelemetryStatsSenderTest {

    @Test
    void flushPostsIsoOccurredAtWithoutJavaTimeModule() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> tenantHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/aggregator/api/v1/events/batch", exchange -> {
            tenantHeader.set(exchange.getRequestHeaders().getFirst("X-DADP-TENANT"));
            requestBody.set(readBody(exchange.getRequestBody()));
            byte[] response = "{\"acceptedCount\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Path tempDir = Files.createTempDirectory("telemetry-stats-test");
        EndpointStorage storage = new EndpointStorage(tempDir.toString(), "crypto-endpoints.json");
        String aggregatorUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        storage.saveEndpoints(
                "http://127.0.0.1:9103",
                "pi_test_wrapper",
                1L,
                true,
                aggregatorUrl,
                "DIRECT",
                500);

        TelemetryStatsSender sender = new TelemetryStatsSender(storage, "pi_test_wrapper", "ds_test_wrapper");
        try {
            sender.sendSqlEvent("SELECT 1", "SELECT", 10L, false);
            invokeFlush(sender);

            assertEquals("pi_test_wrapper", tenantHeader.get());
            assertNotNull(requestBody.get());
            assertTrue(requestBody.get().contains("\"occurredAt\":\""));
            assertTrue(requestBody.get().contains("\"operation\":\"SELECT\""));
            assertTrue(requestBody.get().contains("\"datasourceId\":\"ds_test_wrapper\""));
        } finally {
            shutdownScheduler(sender);
            server.stop(0);
        }
    }

    @Test
    void sendSqlEventReusesCachedEndpointSnapshotUntilRefreshWindow() throws Exception {
        EndpointStorage storage = mock(EndpointStorage.class);
        EndpointStorage.EndpointData endpointData = new EndpointStorage.EndpointData();
        endpointData.setStatsAggregatorEnabled(true);
        endpointData.setStatsAggregatorUrl("http://127.0.0.1:9010");
        endpointData.setStatsAggregatorMode("DIRECT");
        endpointData.setSlowThresholdMs(500);
        endpointData.setBufferMaxEvents(10_000);
        endpointData.setFlushMaxEvents(200);
        endpointData.setFlushIntervalMillis(5_000);
        endpointData.setMaxBatchSize(500);
        endpointData.setMaxPayloadBytes(1_000_000);
        endpointData.setSamplingRate(1.0d);
        endpointData.setIncludeSqlNormalized(false);
        endpointData.setIncludeParams(false);
        endpointData.setNormalizeSqlEnabled(true);
        endpointData.setHttpConnectTimeoutMillis(200);
        endpointData.setHttpReadTimeoutMillis(800);
        endpointData.setRetryOnFailure(0);

        when(storage.loadEndpoints()).thenReturn(endpointData);

        TelemetryStatsSender sender = new TelemetryStatsSender(storage, "pi_test_wrapper", "ds_test_wrapper", 60_000);
        try {
            sender.sendSqlEvent("SELECT 1", "SELECT", 10L, false);
            sender.sendSqlEvent("SELECT 2", "SELECT", 11L, false);

            verify(storage, times(1)).loadEndpoints();
        } finally {
            shutdownScheduler(sender);
        }
    }

    private String readBody(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private void invokeFlush(TelemetryStatsSender sender) throws Exception {
        Method flush = TelemetryStatsSender.class.getDeclaredMethod("flush");
        flush.setAccessible(true);
        flush.invoke(sender);
    }

    private void shutdownScheduler(TelemetryStatsSender sender) throws Exception {
        Field schedulerField = TelemetryStatsSender.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        ScheduledExecutorService scheduler = (ScheduledExecutorService) schedulerField.get(sender);
        scheduler.shutdownNow();
    }
}
