package com.dadp.wrapper.crypto;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
