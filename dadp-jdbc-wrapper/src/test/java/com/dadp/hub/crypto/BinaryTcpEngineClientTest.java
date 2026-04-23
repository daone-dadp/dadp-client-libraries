package com.dadp.hub.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BinaryTcpEngineClientTest {

    @Test
    void sendsLengthPrefixedFrameAndReadsLengthPrefixedResponse() throws Exception {
        byte[] request = new byte[] {1, 2, 3, 4};
        byte[] response = new byte[] {9, 8, 7};
        CountDownLatch handled = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            Thread thread = new Thread(() -> {
                try (Socket socket = server.accept();
                     DataInputStream input = new DataInputStream(socket.getInputStream());
                     DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                    int length = input.readInt();
                    byte[] received = new byte[length];
                    input.readFully(received);
                    assertArrayEquals(request, received);
                    output.writeInt(response.length);
                    output.write(response);
                    output.flush();
                    handled.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "binary-tcp-client-test-server");
            thread.setDaemon(true);
            thread.start();

            BinaryTcpEngineClient client = new BinaryTcpEngineClient("http://127.0.0.1:9003", server.getLocalPort(), 5000, false);
            BinaryTcpEngineClient.Response actual = client.send("encrypt", "/api/encrypt", request);

            assertArrayEquals(response, actual.body);
            assertEquals(request.length, actual.requestBytes);
            assertEquals(response.length, actual.responseBytes);
            org.junit.jupiter.api.Assertions.assertTrue(handled.await(2, TimeUnit.SECONDS));
            client.close();
        }
    }
}
