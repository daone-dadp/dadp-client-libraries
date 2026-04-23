package com.dadp.hub.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

/**
 * Small Java 8 compatible persistent binary TCP client for single engine calls.
 */
final class BinaryTcpEngineClient {

    private static final Logger log = LoggerFactory.getLogger(BinaryTcpEngineClient.class);

    private final String host;
    private final int port;
    private final int timeoutMs;
    private final boolean enableLogging;

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;

    BinaryTcpEngineClient(String engineBaseUrl, int port, int timeoutMs, boolean enableLogging) {
        this.host = resolveHost(engineBaseUrl);
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.enableLogging = enableLogging;
    }

    synchronized Response send(String operation, String endpoint, byte[] payload) {
        IOException firstFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            long startedNs = System.nanoTime();
            long connectStartedNs = startedNs;
            long connectFinishedNs = startedNs;
            long writeStartedNs = startedNs;
            long writeFinishedNs = startedNs;
            long readStartedNs = startedNs;
            long readFinishedNs = startedNs;
            try {
                if (!isConnected()) {
                    connectStartedNs = System.nanoTime();
                    connect();
                    connectFinishedNs = System.nanoTime();
                }

                writeStartedNs = System.nanoTime();
                output.writeInt(payload != null ? payload.length : 0);
                if (payload != null && payload.length > 0) {
                    output.write(payload);
                }
                output.flush();
                writeFinishedNs = System.nanoTime();

                readStartedNs = System.nanoTime();
                int responseLength = input.readInt();
                if (responseLength <= 0) {
                    throw new IOException("Invalid binary TCP response length: " + responseLength);
                }
                byte[] response = new byte[responseLength];
                input.readFully(response);
                readFinishedNs = System.nanoTime();

                if (enableLogging && log.isDebugEnabled()) {
                    log.debug("Wrapper binary TCP request complete: operation={}, endpoint={}, requestBytes={}, responseBytes={}, connectMs={}, writeMs={}, readMs={}, totalMs={}",
                            operation, endpoint, payload != null ? payload.length : 0, response.length,
                            formatMs(connectStartedNs, connectFinishedNs),
                            formatMs(writeStartedNs, writeFinishedNs),
                            formatMs(readStartedNs, readFinishedNs),
                            formatMs(startedNs, readFinishedNs));
                }

                return new Response(
                        response,
                        endpoint,
                        payload != null ? payload.length : 0,
                        response.length,
                        elapsedMs(connectStartedNs, connectFinishedNs),
                        elapsedMs(writeStartedNs, writeFinishedNs),
                        elapsedMs(readStartedNs, readFinishedNs),
                        elapsedMs(startedNs, readFinishedNs));
            } catch (IOException e) {
                firstFailure = e;
                close();
                if (enableLogging) {
                    log.debug("Wrapper binary TCP request failed, attempt={}: operation={}, endpoint={}, error={}",
                            attempt + 1, operation, endpoint, e.getMessage());
                }
            }
        }
        throw new com.dadp.hub.crypto.exception.HubConnectionException(
                "Engine binary TCP connection failed: " + (firstFailure != null ? firstFailure.getMessage() : "unknown"),
                firstFailure);
    }

    synchronized void close() {
        closeQuietly(input);
        closeQuietly(output);
        closeQuietly(socket);
        input = null;
        output = null;
        socket = null;
    }

    private boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private void connect() throws IOException {
        Socket next = new Socket();
        next.setTcpNoDelay(true);
        next.setKeepAlive(true);
        next.connect(new InetSocketAddress(host, port), timeoutMs);
        next.setSoTimeout(timeoutMs);
        socket = next;
        input = new DataInputStream(next.getInputStream());
        output = new DataOutputStream(next.getOutputStream());
        if (enableLogging) {
            log.info("Wrapper binary TCP connected: host={}, port={}", host, port);
        }
    }

    private static String resolveHost(String engineBaseUrl) {
        if (engineBaseUrl == null || engineBaseUrl.trim().isEmpty()) {
            return "localhost";
        }
        try {
            URI uri = URI.create(engineBaseUrl.trim());
            if (uri.getHost() != null && !uri.getHost().trim().isEmpty()) {
                return uri.getHost();
            }
        } catch (Exception ignored) {
            // fall through
        }
        String value = engineBaseUrl.trim();
        int schemeIndex = value.indexOf("://");
        if (schemeIndex >= 0) {
            value = value.substring(schemeIndex + 3);
        }
        int slashIndex = value.indexOf('/');
        if (slashIndex >= 0) {
            value = value.substring(0, slashIndex);
        }
        int colonIndex = value.indexOf(':');
        if (colonIndex >= 0) {
            value = value.substring(0, colonIndex);
        }
        return value.isEmpty() ? "localhost" : value;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // no-op
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // no-op
        }
    }

    private static double elapsedMs(long startedNs, long finishedNs) {
        return (finishedNs - startedNs) / 1_000_000.0;
    }

    private static String formatMs(long startedNs, long finishedNs) {
        return String.format("%.3f", elapsedMs(startedNs, finishedNs));
    }

    static final class Response {
        final byte[] body;
        final String endpoint;
        final int requestBytes;
        final int responseBytes;
        final double connectionOpenMs;
        final double writeMs;
        final double readMs;
        final double totalMs;

        Response(byte[] body, String endpoint, int requestBytes, int responseBytes,
                 double connectionOpenMs, double writeMs, double readMs, double totalMs) {
            this.body = body;
            this.endpoint = endpoint;
            this.requestBytes = requestBytes;
            this.responseBytes = responseBytes;
            this.connectionOpenMs = connectionOpenMs;
            this.writeMs = writeMs;
            this.readMs = readMs;
            this.totalMs = totalMs;
        }
    }
}
