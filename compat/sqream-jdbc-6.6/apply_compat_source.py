from pathlib import Path
a=Path(__file__).parent
p=a/'compat-src/com/sqream/jdbc/connector/messenger/MessengerImpl.java'
s=(a/'baseline-source/com/sqream/jdbc/connector/messenger/MessengerImpl.java').read_text()
s=s.replace('import java.util.logging.Logger;', 'import java.util.logging.Logger;\nimport java.util.concurrent.TimeUnit;\nimport java.util.concurrent.locks.ReentrantLock;')
s=s.replace('private HeartBeatService pingService = new HeartBeatFakeService();', '''private HeartBeatService pingService = new HeartBeatFakeService();
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
    }''')
def method(signature,body):
 global s
 begin=s.index(signature)
 brace=s.index('{',begin)
 depth=1;i=brace+1
 # No braces in Java string literals in replaced method bodies affect this simple scan,
 # so scan lexical strings explicitly.
 quote=None;escape=False
 while depth:
  c=s[i]
  if quote:
   if escape:escape=False
   elif c=='\\':escape=True
   elif c==quote:quote=None
  elif c in ('"',"'"):quote=c
  elif c=='{':depth+=1
  elif c=='}':depth-=1
  i+=1
 s=s[:brace+1]+'\n'+body+'\n    '+s[i-1:]
method('public FetchMetadataDto fetch()', '''        return this.compatIo(() -> {
            this.compatBinaryRead = true;
            String response = this.sendMessage(COMMAND_FETCH, true, false);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_FETCH, response));
            FetchMetadataDto metadata = this.jsonParser.toFetchMetadata(response);
            long remaining = 0;
            for (int i=0; i<metadata.colAmount(); i++) remaining += metadata.getSizeByIndex(i);
            this.compatReadRemaining = remaining;
            this.compatBinaryRead = remaining != 0;
            return metadata;
        }, true);''')
method('public ConnectionStateDto connect(', '''        return this.compatIo(() -> {
            String connStr = MessageFormat.format(CONNECT_DATABASE_TEMPLATE, database, user, password, service);
            String response = this.sendMessage(connStr, true, false);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", connStr, response));
            ConnectionStateDto connState = this.jsonParser.toConnectionState(response);
            this.pingService = HeartBeatServiceFactory.getService(this);
            return connState;
        }, true);''')
method('public void isStatementReconstructed(', '''        this.compatIo(() -> {
            this.validateResponse(this.sendMessage(MessageFormat.format(RECONSTRUCT_STATEMENT_TEMPLATE, statementId), true, false), EVENT_STATEMENT_RECONSTRUCTED);
            this.compatAwaitingReconstruct = false;
            return null;
        }, true);''')
method('public void closeStatement()', '''        this.compatIo(() -> {
            String response = this.sendMessage(COMMAND_CLOSE_STATEMENT, true, false);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", COMMAND_CLOSE_STATEMENT, response));
            this.validateResponse(response, EVENT_STATEMENT_CLOSED);
            this.compatBinaryWrite = false; this.compatBinaryRead = false;
            this.compatAwaitingReconstruct = false;
            return null;
        }, true);''')
method('public void closeConnection()', '''        if (this.compatClosed) return;
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
        }''')
method('public void put(', '''        this.compatIo(() -> {
            this.compatBinaryWrite = true;
            String message = MessageFormat.format(PUT_TEMPLATE, rowCounter);
            this.sendMessage(message, false, false);
            LOGGER.log(Level.FINE, message);
            return null;
        }, true);''')
method('public void isPutted()', '''        this.compatIo(() -> {
            String response = this.socket.sendData(null, true);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", null, response));
            this.validateResponse(response, EVENT_PUTTED);
            this.compatBinaryWrite = false;
            return null;
        }, true);''')
method('public void reconnect(', '''        this.compatIo(() -> {
            // Ownership is acquired and the old generation is invalidated BEFORE
            // replacing the channel. No interrupted/stale PING can use the new channel.
            this.compatAwaitingReconstruct = true;
            this.socket.reconnect(ip, port, useSsl);
            String reconnectStr = MessageFormat.format(RECONNECT_DATABASE_TEMPLATE, database, user, password, service, connectionId, listenerId);
            String response = this.sendMessage(reconnectStr, true, false);
            LOGGER.log(Level.FINE, MessageFormat.format("Request: [{0}], Response: [{1}]", reconnectStr, response));
            return null;
        }, true);''')
method('public void sendBinaryHeader(', '''        this.compatIo(() -> {
            this.compatBinaryWrite = true;
            ByteBuffer header = this.socket.generateHeader(dataLength, false);
            this.socket.sendData(header, false);
            return null;
        }, true);''')
method('public void sendBinaryData(', '''        this.compatIo(() -> {
            this.compatBinaryWrite = true;
            this.socket.sendData(buffer, false);
            return null;
        }, true);''')
method('public void parseHeader()', '''        this.compatIo(() -> { this.socket.parseHeader(); return null; }, true);''')
method('public void fetchBinaryData(', '''        this.compatIo(() -> {
            this.socket.readData(target, size);
            if (this.compatReadRemaining > 0) this.compatReadRemaining = Math.max(0L, this.compatReadRemaining - size);
            if (this.compatReadRemaining == 0) this.compatBinaryRead = false;
            return null;
        }, true);''')
method('private String sendMessage(', '''        return this.compatIo(() -> {
            byte[] messageBytes = message.getBytes();
            ByteBuffer messageBuffer = this.socket.generateHeaderedBuffer(messageBytes.length, true);
            messageBuffer.put(messageBytes);
            return this.socket.sendData(messageBuffer, getResponse);
        }, restartPing);''')
p.write_text(s)
