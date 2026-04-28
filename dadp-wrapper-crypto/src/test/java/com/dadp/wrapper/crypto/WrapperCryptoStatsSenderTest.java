package com.dadp.wrapper.crypto;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WrapperCryptoStatsSenderTest {

    @Test
    void sendsAggregatedHourlyCountsWhenWindowRollsOver() {
        MutableTimeProvider timeProvider = new MutableTimeProvider(LocalDateTime.of(2026, 4, 28, 10, 5, 0));
        CapturingTransport transport = new CapturingTransport();

        WrapperCryptoStatsSender sender = new WrapperCryptoStatsSender(
                "http://hub:9004",
                1000,
                null,
                "1hour",
                timeProvider,
                transport,
                false);
        try {
            sender.recordEncryptSuccess();
            sender.recordDecryptSuccess();
            sender.recordDecryptFailure();

            timeProvider.setNow(LocalDateTime.of(2026, 4, 28, 11, 0, 0));
            sender.flushForTests();

            assertEquals(1, transport.payloads.size());
            Map<String, Object> payload = transport.payloads.get(0);
            assertEquals("1hour", payload.get("aggregationLevel"));
            assertEquals(1L, payload.get("encryptCount"));
            assertEquals(2L, payload.get("decryptCount"));
            assertEquals(2L, payload.get("successCount"));
            assertEquals(1L, payload.get("failureCount"));
            assertEquals("2026-04-28T10:00", payload.get("windowStart"));
        } finally {
            sender.close();
        }
    }

    @Test
    void postsExpectedPayloadAndAuthHeaderToHubEndpoint() throws Exception {
        MutableTimeProvider timeProvider = new MutableTimeProvider(LocalDateTime.of(2026, 4, 28, 10, 5, 0));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> authHeaders = new ArrayList<String>();
        List<String> bodies = new ArrayList<String>();
        server.createContext("/hub/api/v1/stats/wrapper/crypto", exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("X-Hub-Auth"));
            bodies.add(new String(readAll(exchange), StandardCharsets.UTF_8));
            writeJson(exchange, "{\"code\":\"SUCCESS\",\"data\":null}");
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String secret = Base64.getEncoder().encodeToString(new byte[32]);
            WrapperCryptoStatsSender sender = new WrapperCryptoStatsSender(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    1000,
                    new HubInternalAuthHeaderProvider("pi_test", secret),
                    "1hour",
                    timeProvider,
                    new WrapperCryptoStatsSender.HttpTransport(),
                    false);
            try {
                sender.recordEncryptSuccess();
                timeProvider.setNow(LocalDateTime.of(2026, 4, 28, 11, 0, 0));
                sender.flushForTests();
            } finally {
                sender.close();
            }

            assertEquals(1, authHeaders.size());
            assertTrue(authHeaders.get(0).startsWith("pi_test:"));
            assertEquals(1, bodies.size());
            assertTrue(bodies.get(0).contains("\"aggregationLevel\":\"1hour\""));
            assertTrue(bodies.get(0).contains("\"encryptCount\":1"));
            assertTrue(bodies.get(0).contains("\"decryptCount\":0"));
        } finally {
            server.stop(0);
        }
    }

    private static final class MutableTimeProvider implements WrapperCryptoStatsSender.TimeProvider {
        private LocalDateTime now;

        private MutableTimeProvider(LocalDateTime now) {
            this.now = now;
        }

        @Override
        public LocalDateTime now() {
            return now;
        }

        void setNow(LocalDateTime now) {
            this.now = now;
        }
    }

    private static final class CapturingTransport implements WrapperCryptoStatsSender.Transport {
        private final List<Map<String, Object>> payloads = new ArrayList<Map<String, Object>>();

        @Override
        public void send(String hubBaseUrl, int timeoutMillis,
                         HubInternalKeyClient.AuthHeaderProvider authHeaderProvider,
                         WrapperCryptoStatsSender.WindowSnapshot snapshot) {
            payloads.add(snapshot.toPayload());
        }
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static byte[] readAll(HttpExchange exchange) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while ((read = exchange.getRequestBody().read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
