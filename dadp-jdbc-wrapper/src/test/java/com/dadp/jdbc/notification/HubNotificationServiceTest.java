package com.dadp.jdbc.notification;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubNotificationServiceTest {

    @Test
    void sendsExternalNotificationWithTenantHeader() throws Exception {
        AtomicReference<String> tenantHeader = new AtomicReference<String>();
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hub/api/v1/notifications/external", exchange -> {
            tenantHeader.set(exchange.getRequestHeaders().getFirst("X-DADP-Tenant-Id"));
            requestBody.set(readBody(exchange));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            String hubUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            HubNotificationService service = new HubNotificationService(hubUrl, "tenant-1", "alias-1", true);

            service.notifyLocalCryptoFailure(
                    "encrypt",
                    "POLICY01",
                    "KEY_PULL_FAILED",
                    "Runtime execution-key resolve failed",
                    false,
                    false);

            assertEquals("tenant-1", tenantHeader.get());
            assertTrue(requestBody.get().contains("\"type\":\"CRYPTO_ERROR\""));
            assertTrue(requestBody.get().contains("\"entityType\":\"WRAPPER\""));
            assertTrue(requestBody.get().contains("\"entityId\":\"alias-1\""));
            assertTrue(requestBody.get().contains("WRAPPER_LOCAL_CRYPTO_FAILURE"));
            assertTrue(requestBody.get().contains("KEY_PULL_FAILED"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsColumnSizeFailureNotification() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hub/api/v1/notifications/external", exchange -> {
            requestBody.set(readBody(exchange));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            String hubUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            HubNotificationService service = new HubNotificationService(hubUrl, "tenant-1", "alias-1", true);

            service.notifyColumnSizeFailure(
                    "executeUpdate",
                    "customers",
                    "email",
                    "POLICY01",
                    "Encrypted data exceeds column size",
                    true,
                    true);

            assertTrue(requestBody.get().contains("WRAPPER_COLUMN_SIZE_FAILURE"));
            assertTrue(requestBody.get().contains("\"level\":\"WARNING\""));
            assertTrue(requestBody.get().contains("plaintextRetry"));
            assertTrue(requestBody.get().contains("true"));
            assertTrue(requestBody.get().contains("customers"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsDatabaseConnectionFailureNotification() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hub/api/v1/notifications/external", exchange -> {
            requestBody.set(readBody(exchange));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            String hubUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            HubNotificationService service = new HubNotificationService(hubUrl, "tenant-1", "alias-1", true);

            service.notifyDatabaseConnectionFailure(
                    "DB_CONNECTION_LIMIT",
                    "remaining connection slots are reserved",
                    "jdbc:postgresql://db:5434/test");

            assertTrue(requestBody.get().contains("WRAPPER_DATABASE_CONNECTION_FAILURE"));
            assertTrue(requestBody.get().contains("\"type\":\"SYSTEM_ERROR\""));
            assertTrue(requestBody.get().contains("DB_CONNECTION_LIMIT"));
        } finally {
            server.stop(0);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream inputStream = exchange.getRequestBody();
        byte[] buffer = new byte[1024];
        StringBuilder builder = new StringBuilder();
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }
}
