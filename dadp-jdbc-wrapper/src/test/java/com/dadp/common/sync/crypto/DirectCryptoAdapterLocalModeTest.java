package com.dadp.common.sync.crypto;

import com.dadp.common.sync.config.EndpointStorage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectCryptoAdapterLocalModeTest {

    @Test
    void localModeRequestIsForcedToRemote() {
        DirectCryptoAdapter adapter = new DirectCryptoAdapter(false);

        adapter.setCryptoMode(
                "local",
                "http://dadp-hub:9004",
                false,
                1000,
                "wtenant_local",
                true,
                "1day");

        assertFalse(adapter.isLocalCryptoMode());
    }

    @Test
    void engineFailureNeverReturnsPlaintextWhenFailClosed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/encrypt", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        try {
            EndpointStorage.EndpointData endpoint = new EndpointStorage.EndpointData();
            endpoint.setCryptoUrl("http://127.0.0.1:" + server.getAddress().getPort());

            DirectCryptoAdapter adapter = new DirectCryptoAdapter(false);
            adapter.setEndpointData(endpoint);

            assertThrows(RuntimeException.class, () -> adapter.encrypt("plaintext", "INISAFE_POLICY"));
        } finally {
            server.stop(0);
        }
    }
}
