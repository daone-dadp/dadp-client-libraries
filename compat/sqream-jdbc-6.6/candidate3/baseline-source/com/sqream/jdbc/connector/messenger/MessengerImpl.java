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

    private MessengerImpl(SQSocketConnector socket, JsonParser jsonParser) {
        this.socket = socket;
        this.jsonParser = jsonParser;
    }

    public static Messenger getInstance(SQSocketConnector socket, JsonParser jsonParser) {
        return new MessengerImpl(socket, jsonParser);
    }

    @Override
    public FetchMetadataDto fetch() throws ConnException {
        String response = this.sendMessage(COMMAND_FETCH, true, true);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_FETCH, response));
        return this.jsonParser.toFetchMetadata(response);
    }

    @Override
    public ConnectionStateDto connect(String database, String user, String password, String service) throws ConnException {
        String connStr = MessageFormat.format(CONNECT_DATABASE_TEMPLATE, database, user, password, service);
        String response = this.sendMessage(connStr, true, false);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", connStr, response));
        ConnectionStateDto connState = this.jsonParser.toConnectionState(response);
        this.pingService = HeartBeatServiceFactory.getService(this);
        this.pingService.start();
        return connState;
    }

    @Override
    public int openStatement() throws ConnException {
        String response = this.sendMessage(COMMAND_GET_STATEMENT_ID, true, true);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_GET_STATEMENT_ID, response));
        return this.jsonParser.toStatementId(response);
    }

    @Override
    public void isStatementReconstructed(int statementId) throws ConnException {
        this.validateResponse(this.sendMessage(MessageFormat.format(RECONSTRUCT_STATEMENT_TEMPLATE, statementId), true, true), EVENT_STATEMENT_RECONSTRUCTED);
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
        String response = this.sendMessage(COMMAND_CLOSE_STATEMENT, true, true);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_CLOSE_STATEMENT, response));
        this.validateResponse(response, EVENT_STATEMENT_CLOSED);
    }

    @Override
    public void closeConnection() throws ConnException {
        this.pingService.stop();
        String response = this.sendMessage(COMMAND_CLOSE_CONNECTION, true, false);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_CLOSE_CONNECTION, response));
        this.validateResponse(response, EVENT_CONNECTION_CLOSED);
        this.socket.close();
    }

    @Override
    public void put(int rowCounter) throws ConnException {
        String message = MessageFormat.format(PUT_TEMPLATE, rowCounter);
        this.pingService.stop();
        this.sendMessage(message, false, false);
        LOGGER.log(Level.FINE, message);
        this.pingService.start();
    }

    @Override
    public void ping() throws ConnException {
        this.sendMessage(PING, false, false);
        LOGGER.log(Level.FINE, PING);
    }

    @Override
    public void isPutted() throws ConnException {
        String response = this.socket.sendData(null, true);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", null, response));
        this.validateResponse(response, EVENT_PUTTED);
    }

    @Override
    public void execute() throws ConnException {
        String response = this.sendMessage(COMMAND_EXECUTE, true, true);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_EXECUTE, response));
        this.validateResponse(response, EVENT_EXECUTED);
    }

    @Override
    public void reconnect(String ip, int port, boolean useSsl, String database, String user, String password, String service, int connectionId, int listenerId) throws ConnException {
        this.socket.reconnect(ip, port, useSsl);
        String reconnectStr = MessageFormat.format(RECONNECT_DATABASE_TEMPLATE, database, user, password, service, connectionId, listenerId);
        String response = this.sendMessage(reconnectStr, true, true);
        LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", reconnectStr, response));
    }

    @Override
    public StatementStateDto prepareStatement(String statement, int chunkSize, boolean canSupportParams) throws ConnException {
        JsonObject prepareJsonify = Json.object().add("prepareStatement", statement).add("chunkSize", chunkSize).add("canSupportParams", canSupportParams).add("canSupportClusterFiles", true);
        String prepareStr = prepareJsonify.toString(WriterConfig.MINIMAL);
        return this.jsonParser.toStatementState(this.sendMessage(prepareStr, true, true));
    }

    @Override
    public void sendBinaryHeader(long dataLength) throws ConnException {
        this.pingService.stop();
        ByteBuffer header = this.socket.generateHeader(dataLength, false);
        this.socket.sendData(header, false);
        this.pingService.start();
    }

    @Override
    public void sendBinaryData(ByteBuffer buffer) throws ConnException {
        this.pingService.stop();
        this.socket.sendData(buffer, false);
        this.pingService.start();
    }

    @Override
    public void parseHeader() throws ConnException {
        this.socket.parseHeader();
    }

    @Override
    public void fetchBinaryData(ByteBuffer target, int size) throws ConnException {
        this.socket.readData(target, size);
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
        if (restartPing) {
            this.pingService.stop();
            this.pingService.start();
        }
        byte[] messageBytes = message.getBytes();
        ByteBuffer messageBuffer = this.socket.generateHeaderedBuffer(messageBytes.length, true);
        messageBuffer.put(messageBytes);
        return this.socket.sendData(messageBuffer, getResponse);
    }
}
