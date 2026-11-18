/*
 * Decompiled with CFR 0.152.
 */
package com.sqream.jdbc.connector.messenger;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.ParseException;
import com.eclipsesource.json.WriterConfig;
import com.sqream.jdbc.connector.ConnException;
import com.sqream.jdbc.connector.ConnectionStateDto;
import com.sqream.jdbc.connector.FetchMetadataDto;
import com.sqream.jdbc.connector.JsonParser;
import com.sqream.jdbc.connector.QueryTypeInResponse;
import com.sqream.jdbc.connector.QueryTypeOutResponse;
import com.sqream.jdbc.connector.StatementStateDto;
import com.sqream.jdbc.connector.heartbeat.HeartBeatFakeService;
import com.sqream.jdbc.connector.heartbeat.HeartBeatService;
import com.sqream.jdbc.connector.heartbeat.HeartBeatServiceFactory;
import com.sqream.jdbc.connector.messenger.Messenger;
import com.sqream.jdbc.connector.socket.SQSocketConnector;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class MessengerImpl
implements Messenger {
    private static final Logger LOGGER = Logger.getLogger(MessengerImpl.class.getName());
    private static final String CONNECT_DATABASE_TEMPLATE = "'{'\"connectDatabase\":\"{0}\", \"username\":\"{1}\", \"password\":\"{2}\", \"service\":\"{3}\"'}'";
    private static final String PREPARE_STATEMENT_TEMPLATE = "'{'\"prepareStatement\":\"{0}\", \"chunkSize\":{1}'}'";
    private static final String RECONNECT_DATABASE_TEMPLATE = "'{'\"reconnectDatabase\":\"{0}\", \"username\":\"{1}\", \"password\":\"{2}\", \"service\":\"{3}\", \"connectionId\":{4, number, #}, \"listenerId\":{5, number, #}'}'";
    private static final String RECONSTRUCT_STATEMENT_TEMPLATE = "'{'\"reconstructStatement\":{0, number, #}'}'";
    private static final String PUT_TEMPLATE = "'{'\"put\":{0, number, #}'}'";
    private static final String COMMAND_CLOSE_CONNECTION = "{\"closeConnection\":\"closeConnection\"}";
    private static final String COMMAND_CLOSE_STATEMENT = "{\"closeStatement\":\"closeStatement\"}";
    private static final String COMMAND_GET_STATEMENT_ID = "{\"getStatementId\":\"getStatementId\"}";
    private static final String COMMAND_EXECUTE = "{\"execute\":\"execute\"}";
    private static final String COMMAND_FETCH = "{\"fetch\":\"fetch\"}";
    private static final String EVENT_STATEMENT_CLOSED = "{\"statementClosed\":\"statementClosed\"}";
    private static final String EVENT_CONNECTION_CLOSED = "{\"connectionClosed\":\"connectionClosed\"}";
    private static final String EVENT_EXECUTED = "{\"executed\":\"executed\"}";
    private static final String EVENT_PUTTED = "{\"putted\":\"putted\"}";
    private static final String EVENT_STATEMENT_RECONSTRUCTED = "{\"statementReconstructed\":\"statementReconstructed\"}";
    private static final String QUERY_TYPE_IN = "{\"queryTypeIn\":\"queryTypeIn\"}";
    private static final String QUERY_TYPE_OUT = "{\"queryTypeOut\":\"queryTypeOut\"}";
    private static final String PING = "{\"ping\":\"ping\"}";
    private final SQSocketConnector socket;
    private final JsonParser jsonParser;
    private HeartBeatService pingService = new HeartBeatFakeService();
    // Foreground protocol calls remain ordered, while full-duplex idle PING writes
    // can keep a session alive during a response/ACK read.
    private final ReentrantLock compatOwner = new ReentrantLock(true);
    private final ReentrantLock compatWriteOwner = new ReentrantLock(true);
    private volatile boolean compatClosed, compatPoisoned;
    private volatile boolean compatBinaryWrite, compatBinaryRead, compatAwaitingReconstruct;
    private long compatReadRemaining;
    private volatile boolean compatHasOpenedStatement, compatStatementCloseAcked;
    private volatile int compatCurrentStatementId = -1, compatAckedStatementId = -1;
    private volatile long compatCloseAckSequence;
    public long compatStatementAckSequence() { return this.compatCloseAckSequence; }
    public boolean compatMatchesStatementCloseAck(int expectedStatementId, long priorSequence) {
        long sequence = this.compatCloseAckSequence;
        // Resource cancellation does not erase an ACK already received by this execution.
        return sequence > priorSequence && this.compatAckedStatementId == expectedStatementId;
    }
    // Terminal status: 1=hint complete,2=partial,3=TLS omitted,4=hint error,
    // 5=active/unknown cancellation,6=busy writer cancellation,8=local release error.
    private volatile int compatTerminalStatus, compatTerminalHintWritten = -3;
    public int compatTerminalCloseStatus() { return this.compatTerminalStatus; }
    public int compatTerminalHintBytes() { return this.compatTerminalHintWritten; }
    private void compatMarkActive() {
        this.compatHasOpenedStatement = true;
        this.compatStatementCloseAcked = false;
    }
    private static final long COMPAT_OWNER_WAIT_SECONDS = 10L;

    public ReentrantLock compatTransportOwner() { return this.compatWriteOwner; }
    public boolean compatHeartbeatPermitted() {
        return !this.compatClosed && !this.compatPoisoned
                && !this.compatBinaryWrite && !this.compatAwaitingReconstruct;
    }
    private interface CompatIo<T> { T run() throws ConnException; }
    private void compatEnter() throws ConnException {
        if (this.compatClosed || this.compatPoisoned) throw new ConnException("SQream transport is closed or unusable");
        boolean acquired;
        try { acquired = this.compatOwner.tryLock(COMPAT_OWNER_WAIT_SECONDS, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ConnException("Interrupted before obtaining SQream transport ownership");
        }
        if (!acquired) {
            // Never replace a channel while an earlier PING still owns it.
            // Closing this OLD transport unblocks its IO; it is never reused.
            this.compatPoisoned = true;
            try {
                this.pingService.stop();
            } finally {
                try { this.socket.close(); } catch (ConnException ignored) { }
            }
            throw new ConnException("SQream transport ownership timed out; connection is unusable");
        }
        if (this.compatClosed || this.compatPoisoned) {
            this.compatOwner.unlock();
            throw new ConnException("SQream transport is closed or unusable");
        }
    }
    private void compatResumeIdle() {
        if (this.compatHeartbeatPermitted() && this.socket.isOpen()) {
            this.pingService.start();
        }
    }
    private <T> T compatWriteIo(CompatIo<T> operation) throws ConnException {
        if (this.compatClosed || this.compatPoisoned)
            throw new ConnException("SQream transport is closed or unusable");
        boolean acquired;
        try { acquired = this.compatWriteOwner.tryLock(COMPAT_OWNER_WAIT_SECONDS, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ConnException("Interrupted before obtaining SQream write ownership");
        }
        if (!acquired) {
            this.compatPoisoned = true;
            try { this.pingService.stop(); }
            finally { try { this.socket.close(); } catch (ConnException ignored) { } }
            throw new ConnException("SQream write ownership timed out; connection is unusable");
        }
        try {
            if (this.compatClosed || this.compatPoisoned)
                throw new ConnException("SQream transport is closed or unusable");
            return operation.run();
        } finally { this.compatWriteOwner.unlock(); }
    }
    private <T> T compatIo(CompatIo<T> operation, boolean pauseHeartbeat) throws ConnException {
        this.compatEnter();
        Throwable original = null;
        try {
            if (pauseHeartbeat) this.pingService.stop();
            return operation.run();
        } catch (ConnException | RuntimeException | Error failure) {
            original = failure;
            throw failure;
        } finally {
            try {
                try {
                    if (pauseHeartbeat) this.compatResumeIdle();
                } catch (RuntimeException | Error resumeFailure) {
                    if (original == null) throw resumeFailure;
                    if (resumeFailure != original) original.addSuppressed(resumeFailure);
                }
            } finally { this.compatOwner.unlock(); }
        }
    }

    private MessengerImpl(SQSocketConnector socket, JsonParser jsonParser) {
        this.socket = socket;
        this.jsonParser = jsonParser;
    }

    public static Messenger getInstance(SQSocketConnector socket, JsonParser jsonParser) {
        return new MessengerImpl(socket, jsonParser);
    }

    @Override
    public FetchMetadataDto fetch() throws ConnException {
        this.compatMarkActive();
        return this.compatIo(() -> {
            this.compatBinaryRead = true;
            String response = this.sendMessage(COMMAND_FETCH, true, true);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_FETCH, response));
            FetchMetadataDto metadata = this.jsonParser.toFetchMetadata(response);
            long remaining = 0;
            for (int i=0; i<metadata.colAmount(); i++) remaining += metadata.getSizeByIndex(i);
            this.compatReadRemaining = remaining;
            this.compatBinaryRead = remaining != 0;
            return metadata;
        }, true);
    }

    @Override
    public ConnectionStateDto connect(String database, String user, String password, String service) throws ConnException {
        return this.compatIo(() -> {
            String connStr = MessageFormat.format(CONNECT_DATABASE_TEMPLATE, database, user, password, service);
            String response = this.sendMessage(connStr, true, false);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", connStr, response));
            ConnectionStateDto connState = this.jsonParser.toConnectionState(response);
            this.pingService = HeartBeatServiceFactory.getService(this);
            return connState;
        }, true);
    }

    @Override
    public int openStatement() throws ConnException {
        this.compatMarkActive();
        String response = this.sendMessage(COMMAND_GET_STATEMENT_ID, true, true);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_GET_STATEMENT_ID, response));
        int statementId = this.jsonParser.toStatementId(response);
        this.compatCurrentStatementId = statementId;
        return statementId;
    }

    @Override
    public void isStatementReconstructed(int statementId) throws ConnException {
        this.compatIo(() -> {
            this.validateResponse(this.sendMessage(MessageFormat.format(RECONSTRUCT_STATEMENT_TEMPLATE, statementId), true, false), EVENT_STATEMENT_RECONSTRUCTED);
            this.compatAwaitingReconstruct = false;
            return null;
        }, true);
    }

    @Override
    public QueryTypeInResponse queryTypeInput() throws ConnException {
        String response = this.sendMessage(QUERY_TYPE_IN, true, true);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", QUERY_TYPE_IN, response));
        return new QueryTypeInResponse(this.jsonParser.toQueryTypeInput(response), this.jsonParser.safeExtractIntValueByKey(response, "param_type"));
    }

    @Override
    public QueryTypeOutResponse queryTypeOut() throws ConnException {
        String response = this.sendMessage(QUERY_TYPE_OUT, true, true);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", QUERY_TYPE_OUT, response));
        return new QueryTypeOutResponse(this.jsonParser.toQueryTypeOut(response), this.jsonParser.safeExtractLongValueByKey(response, "updatedRows"), this.jsonParser.safeExtractLongValueByKey(response, "getFileSize"));
    }

    @Override
    public void closeStatement() throws ConnException {
        this.compatIo(() -> {
            String response = this.sendMessage(COMMAND_CLOSE_STATEMENT, true, true);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_CLOSE_STATEMENT, response));
            this.validateResponse(response, EVENT_STATEMENT_CLOSED);
            try {
                if (!"statementClosed".equals(Json.parse(response).asObject().getString("statementClosed", "")))
                    throw new ConnException("SQream statement close ACK was not confirmed");
            } catch (ParseException | UnsupportedOperationException malformed) {
                throw new ConnException("SQream statement close ACK was not confirmed");
            }
            this.compatAckedStatementId = this.compatCurrentStatementId;
            this.compatCloseAckSequence++;
            this.compatStatementCloseAcked = true;
            this.compatBinaryWrite = false; this.compatBinaryRead = false;
            this.compatAwaitingReconstruct = false;
            return null;
        }, true);
    }

    @Override
    public synchronized void closeConnection() throws ConnException {
        if (this.compatClosed) return;
        // Terminal close never declares an active/unknown SQL request successful.
        // Prevent new IO first; old IO can only touch this OLD channel.
        this.compatClosed = true;
        Throwable failure = null;
        boolean foregroundOwned = false, writeOwned = false, detached = false;
        try {
            try { this.pingService.stop(); }
            catch (RuntimeException | Error stopFailure) { failure = stopFailure; }
            foregroundOwned = this.compatOwner.tryLock();
            writeOwned = this.compatWriteOwner.tryLock();
            boolean quiesced = foregroundOwned && writeOwned;
            boolean confirmed = quiesced && (!this.compatHasOpenedStatement || this.compatStatementCloseAcked)
                    && !this.compatBinaryWrite && !this.compatAwaitingReconstruct;
            ByteBuffer hint = null;
            int expected = 0;
            if (confirmed) {
                byte[] bytes = COMMAND_CLOSE_CONNECTION.getBytes();
                hint = this.socket.generateHeaderedBuffer(bytes.length, true);
                hint.put(bytes);
                hint.flip();
                expected = hint.remaining();
            }
            this.compatTerminalHintWritten = this.socket.compatTerminateLocal(hint, quiesced);
            detached = true;
            if (!confirmed) this.compatTerminalStatus = writeOwned ? 5 : 6;
            else if (this.compatTerminalHintWritten == expected) this.compatTerminalStatus = 1;
            else if (this.compatTerminalHintWritten == -1) this.compatTerminalStatus = 3;
            else if (this.compatTerminalHintWritten == -2) this.compatTerminalStatus = 4;
            else this.compatTerminalStatus = 2;
        } catch (ConnException | RuntimeException | Error closeFailure) {
            if (failure == null) failure = closeFailure;
            else if (closeFailure != failure) failure.addSuppressed(closeFailure);
            this.compatTerminalStatus = 8;
        } finally {
            if (!detached) {
                try { this.socket.compatTerminateLocal(null, foregroundOwned && writeOwned); }
                catch (ConnException | RuntimeException | Error cleanup) {
                    if (failure == null) failure = cleanup;
                    else if (cleanup != failure) failure.addSuppressed(cleanup);
                }
            }
            try { this.pingService.stop(); }
            catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (cleanup != failure) failure.addSuppressed(cleanup);
            } finally {
                if (writeOwned) this.compatWriteOwner.unlock();
                if (foregroundOwned) this.compatOwner.unlock();
            }
        }
        if (failure instanceof ConnException) throw (ConnException)failure;
        if (failure instanceof RuntimeException) throw (RuntimeException)failure;
        if (failure instanceof Error) throw (Error)failure;
    }

    @Override
    public void put(int rowCounter) throws ConnException {
        this.compatMarkActive();
        this.compatIo(() -> {
            this.compatBinaryWrite = true;
            String message = MessageFormat.format(PUT_TEMPLATE, rowCounter);
            this.sendMessage(message, false, false);
            LOGGER.log(Level.FINE, message);
            return null;
        }, true);
    }

    @Override
    public void ping() throws ConnException {
        this.compatWriteIo(() -> {
            if (!this.compatHeartbeatPermitted()) return null;
            byte[] bytes = PING.getBytes();
            ByteBuffer frame = this.socket.generateHeaderedBuffer(bytes.length, true);
            frame.put(bytes);
            this.socket.sendData(frame, false);
            return null;
        });
        LOGGER.log(Level.FINE, PING);
    }

    @Override
    public void isPutted() throws ConnException {
        this.compatIo(() -> {
            // All binary bytes have been sent by the same flush worker before this
            // ACK read. No PING was permitted inside that frame; now full-duplex
            // idle PING must continue while the server consumes/processes it.
            this.compatBinaryWrite = false;
            this.compatResumeIdle();
            String response = this.socket.sendData(null, true);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", null, response));
            this.validateResponse(response, EVENT_PUTTED);
            return null;
        }, false);
    }

    @Override
    public void execute() throws ConnException {
        this.compatMarkActive();
        String response = this.sendMessage(COMMAND_EXECUTE, true, true);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_EXECUTE, response));
        this.validateResponse(response, EVENT_EXECUTED);
    }

    @Override
    public void reconnect(String ip, int port, boolean useSsl, String database, String user, String password, String service, int connectionId, int listenerId) throws ConnException {
        this.compatIo(() -> {
            this.compatAwaitingReconstruct = true;
            // Quiesce the previous write generation BEFORE replacing the channel.
            this.compatWriteIo(() -> {
                this.pingService.stop();
                this.socket.reconnect(ip, port, useSsl);
                String reconnectStr = MessageFormat.format(RECONNECT_DATABASE_TEMPLATE, database, user, password, service, connectionId, listenerId);
                String response = this.sendMessage(reconnectStr, true, false);
                LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", reconnectStr, response));
                return null;
            });
            return null;
        }, true);
    }

    @Override
    public StatementStateDto prepareStatement(String statement, int chunkSize, boolean canSupportParams) throws ConnException {
        this.compatMarkActive();
        JsonObject prepareJsonify = Json.object().add("prepareStatement", statement).add("chunkSize", chunkSize).add("canSupportParams", canSupportParams).add("canSupportClusterFiles", true);
        String prepareStr = prepareJsonify.toString(WriterConfig.MINIMAL);
        return this.jsonParser.toStatementState(this.sendMessage(prepareStr, true, true));
    }

    @Override
    public void sendBinaryHeader(long dataLength) throws ConnException {
        this.compatIo(() -> {
            this.compatBinaryWrite = true;
            this.compatWriteIo(() -> {
                ByteBuffer header = this.socket.generateHeader(dataLength, false);
                this.socket.sendData(header, false);
                return null;
            });
            return null;
        }, true);
    }

    @Override
    public void sendBinaryData(ByteBuffer buffer) throws ConnException {
        this.compatIo(() -> {
            this.compatBinaryWrite = true;
            this.compatWriteIo(() -> { this.socket.sendData(buffer, false); return null; });
            return null;
        }, true);
    }

    @Override
    public void parseHeader() throws ConnException {
        this.compatIo(() -> {
            this.compatResumeIdle();
            this.socket.parseHeader();
            return null;
        }, false);
    }

    @Override
    public void fetchBinaryData(ByteBuffer target, int size) throws ConnException {
        this.compatIo(() -> {
            this.compatResumeIdle();
            this.socket.readData(target, size);
            if (this.compatReadRemaining > 0) this.compatReadRemaining = Math.max(0L, this.compatReadRemaining - size);
            if (this.compatReadRemaining == 0) this.compatBinaryRead = false;
            return null;
        }, false);
    }

    private String validateResponse(String response, String expected) throws ConnException {
        if (!response.equals(expected)) {
            try {
                this.jsonParser.checkError(response);
            }
            catch (ParseException e) {
                throw new ConnException("Expected message: " + expected + " but got " + response);
            }
        }
        return response;
    }

    private String sendMessage(String message, boolean getResponse, boolean restartPing) throws ConnException {
        return this.compatIo(() -> {
            this.compatWriteIo(() -> {
                byte[] messageBytes = message.getBytes();
                ByteBuffer messageBuffer = this.socket.generateHeaderedBuffer(messageBytes.length, true);
                messageBuffer.put(messageBytes);
                this.socket.sendData(messageBuffer, false);
                return null;
            });
            // Keep heartbeat live during a blocking response, not inside a write.
            if (restartPing) this.compatResumeIdle();
            return getResponse ? this.socket.sendData(null, true) : null;
        }, restartPing);
    }
}
