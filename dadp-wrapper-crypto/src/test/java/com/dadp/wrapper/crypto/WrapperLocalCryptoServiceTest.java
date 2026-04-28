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

class WrapperLocalCryptoServiceTest {

    @Test
    void encryptDecryptUsesHubPolicyAndKeyMaterialApis() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] key = new byte[32];
        String keyData = Base64.getEncoder().encodeToString(key);
        server.createContext("/hub/api/v1/policies/name/customer-policy", exchange -> writeJson(exchange,
                "{\"code\":\"SUCCESS\",\"data\":{\"id\":\"policy-uid-1\",\"policyName\":\"customer-policy\","
                        + "\"keyAlias\":\"customer-key\",\"keyVersion\":1,\"algorithm\":\"A256GCM\"}}"));
        server.createContext("/hub/api/v1/keys/internal/customer-key/1", exchange -> writeJson(exchange,
                "{\"code\":\"SUCCESS\",\"data\":{\"keyAlias\":\"customer-key\",\"keyVersion\":1,"
                        + "\"provider\":\"HUB\",\"algorithm\":\"A256GCM\"}}"));
        server.createContext("/hub/api/v1/keys/internal-data/customer-key/1", exchange -> writeJson(exchange,
                "{\"code\":\"SUCCESS\",\"data\":{\"keyData\":\"" + keyData + "\"}}"));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String hubUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            WrapperLocalCryptoService service = new WrapperLocalCryptoService(hubUrl, 1000);

            String encrypted = service.encrypt("local-wrapper-value", "customer-policy");

            assertTrue(encrypted.startsWith("hub:policy-uid-1:"));
            assertEquals("local-wrapper-value", service.decrypt(encrypted, "customer-policy"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void localServiceRecordsCryptoStatsOnlyForLocalOperations() {
        PolicyMaterial policy = new PolicyMaterial("customer-policy", "policy-uid-1", "customer-key", 1,
                "A256GCM", null, null, null);
        HubPolicyMaterialClient policyClient = new HubPolicyMaterialClient("http://localhost", 1000) {
            @Override
            public PolicyMaterial fetchByName(String policyName) {
                return policy;
            }

            @Override
            public PolicyMaterial fetchByUid(String policyUid) {
                return policy;
            }
        };
        KeyMaterialResolver keyResolver = new KeyMaterialResolver(new HubInternalKeyClient("http://localhost", 1000, null) {
            @Override
            public KeyMetadata fetchKeyMetadata(String keyAlias, int keyVersion) {
                return new KeyMetadata(keyAlias, keyVersion, "HUB", null, null, "A256GCM");
            }

            @Override
            public String fetchKeyData(String keyAlias, int keyVersion) {
                return Base64.getEncoder().encodeToString(new byte[32]);
            }
        });
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

        WrapperLocalCryptoService service = new WrapperLocalCryptoService(
                policyClient,
                keyResolver,
                new LocalAesGcmCrypto(),
                sender);
        try {
            String encrypted = service.encrypt("local-wrapper-value", "customer-policy");
            assertEquals("local-wrapper-value", service.decrypt(encrypted, "customer-policy"));

            timeProvider.setNow(LocalDateTime.of(2026, 4, 28, 11, 0, 0));
            sender.flushForTests();

            assertEquals(1, transport.payloads.size());
            Map<String, Object> payload = transport.payloads.get(0);
            assertEquals(1L, payload.get("encryptCount"));
            assertEquals(1L, payload.get("decryptCount"));
            assertEquals(2L, payload.get("successCount"));
            assertEquals(0L, payload.get("failureCount"));
        } finally {
            service.close();
        }
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
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
}
