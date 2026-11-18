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
    private final ReentrantLock compatOwner = new ReentrantLock(true);
    private volatile boolean compatClosed, compatPoisoned;
    private boolean compatBinaryWrite, compatBinaryRead, compatAwaitingReconstruct;
    private long compatReadRemaining;
    private static final long COMPAT_OWNER_WAIT_SECONDS = 10L;

    public ReentrantLock compatTransportOwner() { return this.compatOwner; }
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
        if (!this.compatClosed && !this.compatPoisoned && !this.compatBinaryWrite
                && !this.compatBinaryRead && !this.compatAwaitingReconstruct && this.socket.isOpen()) {
            this.pingService.start();
        }
    }
    private <T> T compatIo(CompatIo<T> operation, boolean pauseHeartbeat) throws ConnException {
        this.compatEnter();
        try {
            if (pauseHeartbeat) this.pingService.stop();
            return operation.run();
        } finally {
            try {
                if (pauseHeartbeat) this.compatResumeIdle();
            } finally {
                this.compatOwner.unlock();
            }
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
        return this.compatIo(() -> {
            this.compatBinaryRead = true;
            String response = this.sendMessage(COMMAND_FETCH, true, false);
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
        String response = this.sendMessage(COMMAND_GET_STATEMENT_ID, true, true);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_GET_STATEMENT_ID, response));
        return this.jsonParser.toStatementId(response);
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
            String response = this.sendMessage(COMMAND_CLOSE_STATEMENT, true, false);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_CLOSE_STATEMENT, response));
            this.validateResponse(response, EVENT_STATEMENT_CLOSED);
            this.compatBinaryWrite = false; this.compatBinaryRead = false;
            this.compatAwaitingReconstruct = false;
            return null;
        }, true);
    }

    @Override
    public void closeConnection() throws ConnException {
        if (this.compatClosed) return;
        this.compatEnter();
        try {
            this.pingService.stop();
            String response = this.sendMessage(COMMAND_CLOSE_CONNECTION, true, false);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_CLOSE_CONNECTION, response));
            this.validateResponse(response, EVENT_CONNECTION_CLOSED);
        } finally {
            this.compatClosed = true;
            try {
                this.pingService.stop();
            } finally {
                try { this.socket.close(); } finally { this.compatOwner.unlock(); }
            }
        }
    }

    @Override
    public void put(int rowCounter) throws ConnException {
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
        this.sendMessage(PING, false, false);
        LOGGER.log(Level.FINE, PING);
    }

    @Override
    public void isPutted() throws ConnException {
        this.compatIo(() -> {
            String response = this.socket.sendData(null, true);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", null, response));
            this.validateResponse(response, EVENT_PUTTED);
            this.compatBinaryWrite = false;
            return null;
        }, true);
    }

    @Override
    public void execute() throws ConnException {
        String response = this.sendMessage(COMMAND_EXECUTE, true, true);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_EXECUTE, response));
        this.validateResponse(response, EVENT_EXECUTED);
    }

    @Override
    public void reconnect(String ip, int port, boolean useSsl, String database, String user, String password, String service, int connectionId, int listenerId) throws ConnException {
        this.compatIo(() -> {
            // Ownership is acquired and the old generation is invalidated BEFORE
            // replacing the channel. No interrupted/stale PING can use the new channel.
            this.compatAwaitingReconstruct = true;
            this.socket.reconnect(ip, port, useSsl);
            String reconnectStr = MessageFormat.format(RECONNECT_DATABASE_TEMPLATE, database, user, password, service, connectionId, listenerId);
            String response = this.sendMessage(reconnectStr, true, false);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", reconnectStr, response));
            return null;
        }, true);
    }

    @Override
    public StatementStateDto prepareStatement(String statement, int chunkSize, boolean canSupportParams) throws ConnException {
        JsonObject prepareJsonify = Json.object().add("prepareStatement", statement).add("chunkSize", chunkSize).add("canSupportParams", canSupportParams).add("canSupportClusterFiles", true);
        String prepareStr = prepareJsonify.toString(WriterConfig.MINIMAL);
        return this.jsonParser.toStatementState(this.sendMessage(prepareStr, true, true));
    }

    @Override
    public void sendBinaryHeader(long dataLength) throws ConnException {
        this.compatIo(() -> {
            this.compatBinaryWrite = true;
            ByteBuffer header = this.socket.generateHeader(dataLength, false);
            this.socket.sendData(header, false);
            return null;
        }, true);
    }

    @Override
    public void sendBinaryData(ByteBuffer buffer) throws ConnException {
        this.compatIo(() -> {
            this.compatBinaryWrite = true;
            this.socket.sendData(buffer, false);
            return null;
        }, true);
    }

    @Override
    public void parseHeader() throws ConnException {
        this.compatIo(() -> { this.socket.parseHeader(); return null; }, true);
    }

    @Override
    public void fetchBinaryData(ByteBuffer target, int size) throws ConnException {
        this.compatIo(() -> {
            this.socket.readData(target, size);
            if (this.compatReadRemaining > 0) this.compatReadRemaining = Math.max(0L, this.compatReadRemaining - size);
            if (this.compatReadRemaining == 0) this.compatBinaryRead = false;
            return null;
        }, true);
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
            byte[] messageBytes = message.getBytes();
            ByteBuffer messageBuffer = this.socket.generateHeaderedBuffer(messageBytes.length, true);
            messageBuffer.put(messageBytes);
            return this.socket.sendData(messageBuffer, getResponse);
        }, restartPing);
    }
}
