/*
 * Decompiled with CFR 0.152.
 */
package com.sqream.jdbc.connector.socket;

import com.sqream.jdbc.connector.ConnException;
import com.sqream.jdbc.connector.MemoryAllocationService;
import com.sqream.jdbc.connector.socket.SQSocket;
import com.sqream.jdbc.utils.Utils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SQSocketConnector {
    private static final byte PROTOCOL_VERSION = 8;
    private static final int HEADER_SIZE = 10;
    public static final List<Byte> SUPPORTED_PROTOCOLS = new ArrayList<Byte>(Arrays.asList((byte)6, (byte)7, (byte)8));
    public static final int DEFAULT_SERVER_PICKER_PORT = 3108;
    public static final int DEFAULT_SERVER_PICKER_SSL_PORT = 3109;
    private ByteBuffer responseMessage = MemoryAllocationService.allocateByteBuffer(65536).order(ByteOrder.LITTLE_ENDIAN);
    private ByteBuffer header = MemoryAllocationService.allocateByteBuffer(10).order(ByteOrder.LITTLE_ENDIAN);
    private SQSocket socket;

    public SQSocketConnector(SQSocket socket) throws ConnException {
        this.socket = socket;
    }

    // Return bytes written, -1 for TLS hint omission, -2 for hint failure,
    // or -3 when no hint was requested. Local channel release is mandatory.
    public int compatTerminateLocal(ByteBuffer hint, boolean quiesced) throws ConnException {
        int written = -3;
        ConnException hintFailure = null;
        try {
            if (hint != null) {
                try { written = this.socket.compatTryTerminalHint(hint); }
                catch (ConnException failure) { hintFailure = failure; written = -2; }
            }
        } finally {
            try { this.socket.compatDetachLocal(quiesced); }
            catch (ConnException detachFailure) {
                if (hintFailure != null) detachFailure.addSuppressed(hintFailure);
                throw detachFailure;
            } finally {
                if (quiesced) { this.header = null; this.responseMessage = null; }
            }
        }
        return written;
    }

    public void connect(String ip, int port, boolean useSsl, boolean cluster) throws ConnException {
        WorkerUri workerUri = cluster ? this.pickerConnect(ip, port) : new WorkerUri(ip, port);
        this.socket.open(workerUri.getIp(), workerUri.getPort(), useSsl);
    }

    private WorkerUri pickerConnect(String ip, int port) throws ConnException {
        this.socket.open(ip, port, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Object> future = executor.submit(() -> {
            ByteBuffer response_buffer = this.reconnectToNode();
            byte[] ip_bytes = new byte[response_buffer.getInt()];
            response_buffer.get(ip_bytes);
            String workerIp = new String(ip_bytes, StandardCharsets.UTF_8);
            int WorkerPort = response_buffer.getInt();
            this.socket.close();
            return new WorkerUri(workerIp, WorkerPort);
        });
        try {
            WorkerUri workerUri = (WorkerUri)future.get(5L, TimeUnit.SECONDS);
            executor.shutdown();
            return workerUri;
        }
        catch (Exception e) {
            try {
                future.cancel(true);
            }
            catch (Exception exception) {
                // empty catch block
            }
            executor.shutdown();
            if (port != 3108 && port != 3109) {
                throw new ConnException("Connection timed out. For cluster=true it is recommended to use port 3108 or 3109 for ssl.");
            }
            throw new ConnException(e);
        }
    }

    public void reconnect(String ip, int port, boolean useSsl) throws ConnException {
        this.socket.close();
        this.socket.open(ip, port, useSsl);
    }

    public ByteBuffer generateHeaderedBuffer(long dataLength, boolean is_text_msg) {
        return MemoryAllocationService.allocateByteBuffer(10 + (int)dataLength).order(ByteOrder.LITTLE_ENDIAN).put((byte)8).put(is_text_msg ? (byte)1 : 2).putLong(dataLength);
    }

    public ByteBuffer generateHeader(long dataLength, boolean is_text_msg) {
        return MemoryAllocationService.allocateByteBuffer(10).order(ByteOrder.LITTLE_ENDIAN).put((byte)8).put(is_text_msg ? (byte)1 : 2).putLong(dataLength);
    }

    public int parseHeader() throws ConnException {
        this.header.clear();
        this.readData(this.header, 10);
        byte userProtocolVersion = this.header.get();
        if (!SUPPORTED_PROTOCOLS.contains(userProtocolVersion)) {
            StringJoiner joiner = new StringJoiner(", ");
            SUPPORTED_PROTOCOLS.forEach(newElement -> joiner.add(newElement.toString()));
            if (this.isRedirection(this.header)) {
                throw new ConnException("Connection error. Connected to cluster but cluster=false is in connection string, did you mean cluster=true?");
            }
            throw new ConnException(String.format("Unsupported protocol version - supported versions are %s, but got %s", joiner.toString(), userProtocolVersion));
        }
        this.header.get();
        long responseLength = this.header.getLong();
        return (int)responseLength;
    }

    public String sendData(ByteBuffer data, boolean get_response) throws ConnException {
        if (data != null) {
            data.flip();
            while (data.hasRemaining()) {
                this.socket.write(data);
            }
        }
        if (get_response) {
            int msg_len = this.parseHeader();
            if (msg_len > 64000) {
                this.responseMessage = MemoryAllocationService.allocateByteBuffer(msg_len);
            }
            this.responseMessage.clear();
            this.responseMessage.limit(msg_len);
            this.readData(this.responseMessage, msg_len);
        }
        return get_response ? Utils.decode(this.responseMessage) : "";
    }

    public int readData(ByteBuffer response, int msgLen) throws ConnException {
        int totalBytesRead;
        int bytesRead;
        if (msgLen > response.capacity()) {
            throw new ConnException("Attempting to read more data than supplied bytebuffer allows");
        }
        for (totalBytesRead = 0; totalBytesRead < msgLen || msgLen == 0; totalBytesRead += bytesRead) {
            bytesRead = this.socket.read(response);
            if (bytesRead != -1) continue;
            throw new ConnException("Socket closed. Last buffer written: " + String.valueOf(response));
        }
        response.flip();
        return totalBytesRead;
    }

    public void close() throws ConnException {
        this.socket.close();
        this.header = null;
        this.responseMessage = null;
    }

    public boolean isOpen() {
        return this.socket.isOpen();
    }

    private ByteBuffer reconnectToNode() throws ConnException {
        ByteBuffer response_buffer = MemoryAllocationService.allocateByteBuffer(65536).order(ByteOrder.LITTLE_ENDIAN);
        response_buffer.clear();
        int bytes_read = this.socket.read(response_buffer);
        response_buffer.flip();
        if (bytes_read == -1) {
            throw new ConnException("Socket closed when trying to connect to server picker");
        }
        return response_buffer;
    }

    private boolean isRedirection(ByteBuffer header) {
        header.position(0);
        byte firstByteAsInt = header.get();
        header.position(0);
        int firstInt = header.getInt();
        return firstByteAsInt == firstInt;
    }

    private static class WorkerUri {
        private final String ip;
        private final int port;

        public WorkerUri(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }

        public int getPort() {
            return this.port;
        }

        public String getIp() {
            return this.ip;
        }
    }
}

