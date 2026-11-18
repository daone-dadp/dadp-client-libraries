/*
 * Decompiled with CFR 0.152.
 */
package com.sqream.jdbc.connector.socket;

import com.sqream.jdbc.connector.ConnException;
import com.sqream.jdbc.connector.MemoryAllocationService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import tlschannel.ClientTlsChannel;
import tlschannel.TlsChannel;

public class SQSocket {
    private static final Logger LOGGER = Logger.getLogger(SQSocket.class.getName());
    private static final String TLS_PROTOCOL_VERSION = "TLSv1.2";
    public static final int BUFFER_SIZE = 65536;
    private volatile SocketChannel socketChannel;
    private final Object compatChannelState = new Object();
    private TlsChannel tlsChannel;
    private ByteBuffer buffer;
    private String ip;
    private int port;
    private boolean useSsl;
    private volatile boolean compatLocallyDetached;
    private volatile boolean compatTlsCleanupFailed;

    // The hint is an optional terminal control frame, never SQL data.
    // Caller quiesces foreground and heartbeat IO before using this method.
    public int compatTryTerminalHint(ByteBuffer frame) throws ConnException {
        if (this.useSsl) return -1; // Never write plaintext onto a TLS channel.
        try {
            if (this.socketChannel == null || !this.socketChannel.isOpen()) return 0;
            this.socketChannel.configureBlocking(false);
            return this.socketChannel.write(frame); // One nonblocking attempt; no retry loop.
        } catch (IOException | RuntimeException failure) {
            throw new ConnException(failure);
        }
    }

    public void compatDetachLocal(boolean quiesced) throws ConnException {
        SocketChannel detached;
        synchronized (this.compatChannelState) {
            this.compatLocallyDetached = true; // Permanent: terminal abort cannot be reopened.
            detached = this.socketChannel;
        }
        IOException failure = null;
        try {
            if (detached != null) detached.close();
        } catch (IOException closeFailure) { failure = closeFailure; }
        // Closing the underlying channel FIRST prevents TLS close from waiting
        // for a peer. Only touch wrapper buffers if no reader/writer can own them.
        if (quiesced) {
            if (this.tlsChannel != null) {
                try { this.tlsChannel.close(); }
                catch (IOException tlsCleanup) { this.compatTlsCleanupFailed = true; }
            }
            this.buffer = null;
        }
        if (failure != null) throw new ConnException(failure);
    }

    public boolean compatTlsCleanupFailed() { return this.compatTlsCleanupFailed; }


    public SQSocket() throws ConnException {
        LOGGER.log(Level.FINE, MessageFormat.format("Open socket with params: ip=[{0}], port=[{1}], useSsl=[{2}]", this.ip, this.port, this.useSsl));
    }

    public int read(ByteBuffer result) throws ConnException {
        try {
            int readBytes;
            this.buffer.clear();
            this.buffer.limit(Math.min(result.remaining(), this.buffer.limit()));
            int n = readBytes = this.useSsl ? this.tlsChannel.read(this.buffer) : this.socketChannel.read(this.buffer);
            if (readBytes > 0) {
                this.buffer.rewind();
                this.buffer.limit(readBytes);
                result.put(this.buffer);
            }
            return readBytes;
        }
        catch (IOException e) {
            throw new ConnException(e);
        }
    }

    public void write(ByteBuffer data) throws ConnException {
        try {
            if (this.useSsl) {
                this.tlsChannel.write(data);
            } else {
                this.socketChannel.write(data);
            }
        }
        catch (IOException e) {
            throw new ConnException(e);
        }
    }

    boolean isOpen() {
        return !this.compatLocallyDetached && (this.useSsl ? this.tlsChannel.isOpen() : this.socketChannel.isOpen());
    }

    public void close() throws ConnException {
        try {
            if (this.useSsl && this.tlsChannel.isOpen()) {
                this.tlsChannel.close();
            }
            if (this.socketChannel.isOpen()) {
                this.socketChannel.close();
            }
            if (this.buffer != null) {
                this.buffer = null;
            }
        }
        catch (IOException e) {
            throw new ConnException(e);
        }
    }

    public void open(String ip, int port, boolean useSsl) {
        this.ip = ip;
        this.port = port;
        this.useSsl = useSsl;
        try {
            SocketChannel fresh = SocketChannel.open();
            synchronized (this.compatChannelState) {
                if (this.compatLocallyDetached) {
                    fresh.close();
                    throw new java.nio.channels.ClosedChannelException();
                }
                this.socketChannel = fresh; // Register before potentially blocking connect.
            }
            // Terminal abort never waits for this connect; it closes the registered raw FD.
            fresh.connect(new InetSocketAddress(ip, port));
            if (this.compatLocallyDetached) {
                fresh.close();
                throw new java.nio.channels.ClosedChannelException();
            }
            if (useSsl) {
                SSLContext ssl_context = null;
                ssl_context = SSLContext.getInstance(TLS_PROTOCOL_VERSION);
                ssl_context.init(null, new TrustManager[]{new X509TrustManager(){

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }}, null);
                this.tlsChannel = ClientTlsChannel.newBuilder((ByteChannel)this.socketChannel, ssl_context).build();
            }
            this.buffer = MemoryAllocationService.allocateDirectByteBuffer(65536);
            if (this.compatLocallyDetached) {
                fresh.close();
                throw new java.nio.channels.ClosedChannelException();
            }
        }
        catch (IOException | KeyManagementException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}

