package com.dadp.common.sync.crypto;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectCryptoAdapterLocalModeTest {

    @Test
    void localEncryptPullsPolicyAndExecutionKeyFromHub() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] key = new byte[32];
        String keyData = Base64.getEncoder().encodeToString(key);
        AtomicInteger policyCalls = new AtomicInteger();
        AtomicInteger keyCalls = new AtomicInteger();

        server.createContext("/hub/api/v1/runtime/execution-keys/resolve", exchange -> {
            keyCalls.incrementAndGet();
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("wtenant_local", exchange.getRequestHeaders().getFirst("X-DADP-Tenant-Id"));
            writeJson(exchange,
                    "{\"policyCode\":\"PART1234\",\"policyVersion\":1,"
                            + "\"keyAlias\":\"default\",\"keyVersion\":1,\"providerType\":\"HUB\","
                            + "\"providerVendor\":\"\",\"algorithm\":\"AES_256\","
                            + "\"materialType\":\"RAW_AES_256\",\"materialEncoding\":\"base64\","
                            + "\"executionKeyBase64\":\"" + keyData + "\",\"cacheTtlSeconds\":300,"
                            + "\"expiresAt\":\"2099-01-01T00:00:00Z\"}");
        });
        server.createContext("/hub/api/v1/runtime/policies/PART1234", exchange -> {
            policyCalls.incrementAndGet();
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("wtenant_local", exchange.getRequestHeaders().getFirst("X-DADP-Tenant-Id"));
            writeJson(exchange,
                    "{\"policyCode\":\"PART1234\",\"name\":\"partial-prefix3\",\"version\":1,"
                            + "\"status\":\"ACTIVE\",\"algorithm\":\"A256GCM\","
                            + "\"metadata\":{\"partialEncryption\":true,\"plainStart\":0,\"plainLength\":3}}");
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String hubUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            DirectCryptoAdapter adapter = new DirectCryptoAdapter(false);
            adapter.setCryptoMode("local", hubUrl, false, 1000, "wtenant_local", false, "1hour");

            String encrypted = adapter.encrypt("01012345678", "PART1234");

            assertTrue(encrypted.startsWith("010::ENC::hub:PART1234:"));
            assertEquals(1, keyCalls.get());
            assertEquals(1, policyCalls.get());
        } finally {
            server.stop(0);
        }
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
