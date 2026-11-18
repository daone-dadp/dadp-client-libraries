/*
 * Decompiled with CFR 0.152.
 */
package com.sqream.jdbc.connector;

import com.sqream.jdbc.ConnectionParams;
import com.sqream.jdbc.SQConnection;
import com.sqream.jdbc.connector.BlockDto;
import com.sqream.jdbc.connector.ByteBufferPool;
import com.sqream.jdbc.connector.ConnException;
import com.sqream.jdbc.connector.ConnTimeoutException;
import com.sqream.jdbc.connector.Connector;
import com.sqream.jdbc.connector.ExecuteResult;
import com.sqream.jdbc.connector.FlushService;
import com.sqream.jdbc.connector.InsertValidator;
import com.sqream.jdbc.connector.MemoryAllocationService;
import com.sqream.jdbc.connector.SQArrayGeneric;
import com.sqream.jdbc.connector.enums.StatementType;
import com.sqream.jdbc.connector.fetchService.FetchService;
import com.sqream.jdbc.connector.fetchService.FetchServiceFactory;
import com.sqream.jdbc.connector.serverAPI.SqreamConnection;
import com.sqream.jdbc.connector.serverAPI.SqreamConnectionFactory;
import com.sqream.jdbc.connector.serverAPI.Statement.SqreamCreatedStatement;
import com.sqream.jdbc.connector.serverAPI.Statement.SqreamExecutedStatement;
import com.sqream.jdbc.connector.storage.FlushStorage;
import com.sqream.jdbc.connector.storage.fetchStorage.EmptyFetchStorage;
import com.sqream.jdbc.connector.storage.fetchStorage.FetchStorage;
import com.sqream.jdbc.connector.storage.fetchStorage.FetchStorageImpl;
import com.sqream.jdbc.file.GetFile;
import com.sqream.jdbc.file.PutFile;
import com.sqream.jdbc.utils.Utils;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConnectorImpl
implements Connector {
    private static final Logger LOGGER = Logger.getLogger(ConnectorImpl.class.getName());
    private static final String DEFAULT_CHARACTER_CODES = "ascii";
    private static final ZoneId SYSTEM_TZ = ZoneId.systemDefault();
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final int BYTE_BUFFER_POOL_SIZE = 3;
    private String varcharEncoding = "ascii";
    private String database;
    private String user;
    private String password;
    private String service;
    private ConnectionParams connParams;
    private boolean canSupportParams = false;
    public static final int ROWS_PER_FLUSH_LIMIT = 1000000;
    public static final int BYTES_PER_FLUSH_LIMIT = 0xC800000;
    private FetchStorage fetchStorage;
    private FlushStorage flushStorage;
    private int fetchLimit = 0;
    private int fetchSize = 0;
    private int totalRowCounter;
    private AtomicBoolean IsCancelStatement = new AtomicBoolean(false);
    private InsertValidator flushValidator;
    private InsertValidator fetchValidator;
    private FlushService flushService;
    private FetchService fetchService;
    private ByteBufferPool byteBufferPool;
    private SqreamConnection sqreamConnection;
    private SqreamExecutedStatement sqreamExecutedStatement;
    private SqreamCreatedStatement sqreamCreatedStatement;
    private int timeout = 0;
    private int insertBufferLimit = 0xC800000;

    @Override
    public SqreamExecutedStatement getSqreamExecutedStatement() {
        return this.sqreamExecutedStatement;
    }

    public ConnectorImpl(ConnectionParams connParams) throws ConnException {
        this(connParams, false);
    }

    public ConnectorImpl(ConnectionParams connParams, boolean canSupportParams) throws ConnException {
        this.connParams = connParams;
        this.canSupportParams = canSupportParams;
        if (this.connParams.getInsertBuffer() != null && this.connParams.getInsertBuffer() > 0) {
            this.insertBufferLimit = this.connParams.getInsertBuffer();
        }
    }

    public void flushInitial() throws ConnException {
        if (!this.flushStorage.next()) {
            this.flush();
        }
    }

    public void flushAndClose() {
        this.flush();
        this.flushService.close();
    }

    private int flush() {
        BlockDto blockForFlush = this.flushStorage.getBlock();
        int rowsFlush = blockForFlush.getFillSize();
        if (rowsFlush > 0) {
            this.flushService.process(blockForFlush, this.byteBufferPool);
            this.flushStorage.setBlock(this.byteBufferPool.getBlock());
        }
        return rowsFlush;
    }

    @Override
    public int connect(String database, String user, String password, String service) throws ConnException {
        this.database = database;
        this.user = user;
        this.password = password;
        this.service = service;
        this.connParams = ConnectionParams.builder(true).from(this.connParams).dbName(database).user(user).password(password).service(service).build();
        this.sqreamConnection = SqreamConnectionFactory.openConnection(this.connParams);
        return this.sqreamConnection.getId();
    }

    @Override
    public ExecuteResult execute(String statement) throws ConnException {
        int defaultChunksize = (int)Math.pow(10.0, 6.0);
        if (this.timeout > 0) {
            return this.execute(statement, defaultChunksize, this.timeout);
        }
        return this.execute(statement, defaultChunksize);
    }

    private ExecuteResult execute(String statement, int chunkSize, int timeout) throws ConnException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ExecuteResult> future = executor.submit(() -> this.execute(statement, chunkSize));
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        }
        catch (InterruptedException | ExecutionException e) {
            throw new ConnException(e);
        }
        catch (TimeoutException e) {
            future.cancel(true);
            throw new ConnTimeoutException(e);
        }
    }

    @Override
    public ExecuteResult execute(String statement, int chunkSize) throws ConnException {
        LOGGER.log(Level.FINE, MessageFormat.format("Statement=[{0}], ChunkSize=[{1}]", statement, chunkSize));
        if (chunkSize < 0) {
            throw new ConnException("chunk size should be positive, got " + chunkSize);
        }
        if (this.sqreamExecutedStatement != null && this.sqreamExecutedStatement.isOpen()) {
            try {
                this.sqreamExecutedStatement.close();
            }
            catch (Exception e) {
                throw new ConnException(e);
            }
        }
        this.sqreamCreatedStatement = this.sqreamConnection.createStatement();
        this.sqreamExecutedStatement = this.sqreamCreatedStatement.prepare(statement, this.canSupportParams).execute();
        this.flushValidator = new InsertValidator(this.sqreamExecutedStatement.getInputMeta());
        this.fetchValidator = new InsertValidator(this.sqreamExecutedStatement.getOutputMeta());
        String putFilePath = this.sqreamExecutedStatement.getStatementDto().getPutFile();
        String getFilePath = this.sqreamExecutedStatement.getStatementDto().getGetFile();
        long fileSize = this.sqreamExecutedStatement.getStatementDto().getFileSize();
        if (this.sqreamExecutedStatement.getType().equals((Object)StatementType.PARAMED_INSERT) || this.sqreamExecutedStatement.getType().equals((Object)StatementType.PARAMED_UPDATE_DELETE) || putFilePath != null || this.sqreamExecutedStatement.getType().equals((Object)StatementType.PARAMED_SELECT)) {
            int rowsPerFlush = Utils.calcRowLimit(this.sqreamExecutedStatement.getInputMeta(), 1000000, this.insertBufferLimit);
            BlockDto block = new MemoryAllocationService().buildBlock(this.sqreamExecutedStatement.getInputMeta(), rowsPerFlush);
            this.flushService = FlushService.getInstance(this.sqreamExecutedStatement, this.connParams.getParamedQueryTimeoutMin());
            this.flushStorage = new FlushStorage(this.sqreamExecutedStatement.getInputMeta(), block, this.insertBufferLimit);
            this.byteBufferPool = this.createByteBufferPool(rowsPerFlush, this.sqreamExecutedStatement.getType());
        } else if (this.sqreamExecutedStatement.getType().equals((Object)StatementType.QUERY) && getFilePath == null) {
            this.initializeFetch();
        }
        if (putFilePath != null) {
            PutFile putFile = new PutFile(putFilePath, this.sqreamExecutedStatement.getInputMeta(), this.sqreamExecutedStatement.getStatementDto().getLimitFileSize());
            putFile.sendFileToServer(this.sqreamExecutedStatement);
        } else if (getFilePath != null) {
            GetFile getFile = new GetFile(getFilePath, fileSize);
            getFile.saveFileFromServer(this.sqreamExecutedStatement);
        }
        return new ExecuteResult(this.sqreamExecutedStatement.getId(), this.sqreamExecutedStatement.getUpdatedRows());
    }

    public void initializeFetch() throws ConnException {
        this.fetchService = FetchServiceFactory.getService(this.sqreamExecutedStatement, this.fetchSize);
        this.totalRowCounter = 0;
        this.fetchService.process(this.fetchLimit);
        BlockDto fetchedBlock = this.fetchService.getBlock();
        this.fetchStorage = fetchedBlock != null ? new FetchStorageImpl(this.sqreamExecutedStatement.getOutputMeta(), fetchedBlock) : new EmptyFetchStorage();
        if (this.fetchService.isClosed() && !this.sqreamExecutedStatement.getType().equals((Object)StatementType.PARAMED_SELECT)) {
            this.close();
        }
    }

    @Override
    public boolean next() throws ConnException {
        if (this.sqreamExecutedStatement.getType().equals((Object)StatementType.PARAMED_INSERT) || this.sqreamExecutedStatement.getType().equals((Object)StatementType.PARAMED_UPDATE_DELETE)) {
            if (!this.flushStorage.next()) {
                this.flush();
            }
        } else if (this.sqreamExecutedStatement.getType().equals((Object)StatementType.QUERY) || this.sqreamExecutedStatement.getType().equals((Object)StatementType.PARAMED_SELECT)) {
            if (this.fetchLimit != 0 && this.totalRowCounter == this.fetchLimit) {
                return false;
            }
            if (!this.fetchStorage.next()) {
                BlockDto fetchedBlock = this.fetchService.getBlock();
                if (fetchedBlock == null || fetchedBlock.getFillSize() == 0) {
                    if (this.sqreamExecutedStatement.getType().equals((Object)StatementType.QUERY)) {
                        this.close();
                    }
                    return false;
                }
                this.fetchStorage.setBlock(fetchedBlock);
            }
            ++this.totalRowCounter;
        } else {
            if (this.sqreamExecutedStatement.getType().equals((Object)StatementType.NON_QUERY)) {
                throw new ConnException("Calling next() on a non insert / select query");
            }
            throw new ConnException("Calling next() on a statement type different than INSERT / SELECT / DML: " + this.sqreamExecutedStatement.getType().getValue());
        }
        return true;
    }

    @Override
    public void close() throws ConnException {
        LOGGER.log(Level.FINE, MessageFormat.format("Close statement: openStatement=[{0}], statementType=[{1}]]", new Object[]{this.isOpenStatement(), this.sqreamExecutedStatement != null ? this.sqreamExecutedStatement.getType() : null}));
        if (this.isOpen() && this.sqreamExecutedStatement != null && this.sqreamExecutedStatement.isOpen()) {
            if (this.sqreamExecutedStatement.getType().equals((Object)StatementType.PARAMED_INSERT) || this.sqreamExecutedStatement.getType().equals((Object)StatementType.PARAMED_UPDATE_DELETE)) {
                this.flush();
                this.flushService.close();
                if (this.byteBufferPool != null) {
                    this.byteBufferPool.close();
                }
            }
            try {
                this.sqreamExecutedStatement.close();
            }
            catch (Exception e) {
                throw new ConnException(e);
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public void cancel(SQConnection connection) throws SQLException {
        if (this.checkCancelStatement().getAndSet(true)) {
            return;
        }
        if (!this.isOpenStatement()) {
            return;
        }
        int statementId = this.getStatementId();
        String sql = "select stop_statement(" + statementId + ")";
        ConnectorImpl cancel = null;
        try {
            cancel = new ConnectorImpl(connection.getParams());
            cancel.connect(connection.getParams().getDbName(), connection.getParams().getUser(), connection.getParams().getPassword(), connection.getParams().getService());
            cancel.execute(sql);
            this.close();
        }
        catch (Exception e) {
        }
        finally {
            block16: {
                if (cancel != null && cancel.isOpen()) {
                    try {
                        if (cancel.isOpenStatement()) {
                            cancel.close();
                        }
                        cancel.closeConnection();
                    }
                    catch (Exception e) {
                        String message = e.getMessage();
                        if (message.contains("The statement has already ended")) break block16;
                        throw new SQLException(e);
                    }
                }
            }
            this.IsCancelStatement.set(false);
        }
    }

    @Override
    public boolean closeConnection() throws ConnException {
        if (this.sqreamConnection != null && this.sqreamConnection.isOpen()) {
            if (this.sqreamExecutedStatement != null && this.sqreamExecutedStatement.isOpen()) {
                this.close();
            }
            try {
                this.sqreamConnection.close();
                if (this.fetchStorage != null) {
                    this.fetchStorage.close();
                    this.fetchStorage = null;
                }
            }
            catch (Exception e) {
                throw new ConnException(e);
            }
        }
        return true;
    }

    @Override
    public Boolean getBoolean(int colNum) {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getBoolean(colNum - 1);
    }

    @Override
    public Byte getUbyte(int colNum) {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getUbyte(colNum - 1);
    }

    @Override
    public Short getShort(int colNum) {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getShort(colNum - 1);
    }

    @Override
    public Integer getInt(int colNum) {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getInt(colNum - 1);
    }

    @Override
    public Long getLong(int colNum) {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getLong(colNum - 1);
    }

    @Override
    public Float getFloat(int colNum) {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getFloat(colNum - 1);
    }

    @Override
    public Double getDouble(int colNum) {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getDouble(colNum - 1);
    }

    @Override
    public BigDecimal getBigDecimal(int colNum) {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getBigDecimal(colNum - 1);
    }

    @Override
    public String getVarchar(int colNum) {
        int colIndex = colNum - 1;
        this.fetchValidator.validateColumnIndex(colIndex);
        return this.fetchStorage.getVarchar(colIndex, this.varcharEncoding);
    }

    @Override
    public String getNvarchar(int colNum) {
        int colIndex = colNum - 1;
        this.fetchValidator.validateColumnIndex(colIndex);
        return this.fetchStorage.getNvarchar(colIndex, UTF8);
    }

    @Override
    public Date getDate(int colNum, ZoneId zone) {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getDate(colNum - 1, zone);
    }

    @Override
    public Timestamp getDatetime(int colNum, ZoneId zone) {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getTimestamp(colNum - 1, zone);
    }

    @Override
    public Array getArray(int colNum) throws ConnException {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getArray(colNum - 1);
    }

    @Override
    public OffsetDateTime getOffsetDateTime(int colNum) throws ConnException {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getOffsetDateTime(colNum - 1);
    }

    @Override
    public Instant getInstant(int colNum) throws ConnException {
        this.fetchValidator.validateColumnIndex(colNum - 1);
        return this.fetchStorage.getInstant(colNum - 1);
    }

    @Override
    public Date getDate(int colNum) {
        return this.getDate(colNum, SYSTEM_TZ);
    }

    @Override
    public Timestamp getDatetime(int colNum) {
        return this.getDatetime(colNum, SYSTEM_TZ);
    }

    @Override
    public Boolean getBoolean(String colName) {
        return this.getBoolean(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public Byte getUbyte(String colName) {
        return this.getUbyte(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public Short getShort(String colName) {
        return this.getShort(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public Integer getInt(String colName) {
        return this.getInt(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public Long getLong(String colName) {
        return this.getLong(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public Float getFloat(String colName) {
        return this.getFloat(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public Double getDouble(String colName) {
        return this.getDouble(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public String getVarchar(String colName) {
        return this.getVarchar(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public String getNvarchar(String colName) {
        return this.getNvarchar(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public Date getDate(String colName) {
        return this.getDate(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public Date getDate(String colName, ZoneId zone) {
        return this.getDate(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName), zone);
    }

    @Override
    public Timestamp getDatetime(String colName) {
        return this.getDatetime(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public Timestamp getDatetime(String colName, ZoneId zone) {
        return this.getDatetime(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName), zone);
    }

    @Override
    public OffsetDateTime getOffsetDateTime(String col_name) throws ConnException {
        return this.getOffsetDateTime(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(col_name));
    }

    @Override
    public Instant getInstant(String col_name) throws ConnException {
        return this.getInstant(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(col_name));
    }

    @Override
    public BigDecimal getBigDecimal(String colName) {
        return this.getBigDecimal(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public Array getArray(String colName) throws ConnException {
        return this.getArray(this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName));
    }

    @Override
    public int getTimeout() {
        return this.timeout;
    }

    @Override
    public boolean setBoolean(int colNum, Boolean value) {
        this.flushValidator.validateSet(colNum - 1, value);
        this.flushStorage.setBoolean(colNum - 1, value);
        return true;
    }

    @Override
    public boolean setUbyte(int colNum, Byte value) {
        this.flushValidator.validateSet(colNum - 1, value);
        this.flushStorage.setUbyte(colNum - 1, value);
        return true;
    }

    @Override
    public boolean setShort(int colNum, Short value) {
        this.flushValidator.validateSet(colNum - 1, value);
        this.flushStorage.setShort(colNum - 1, value);
        return true;
    }

    @Override
    public boolean setInt(int colNum, Integer value) {
        this.flushValidator.validateSet(colNum - 1, value);
        this.flushStorage.setInt(colNum - 1, value);
        return true;
    }

    @Override
    public boolean setLong(int colNum, Long value) {
        this.flushValidator.validateSet(colNum - 1, value);
        this.flushStorage.setLong(colNum - 1, value);
        return true;
    }

    @Override
    public boolean setFloat(int colNum, Float value) {
        this.flushValidator.validateSet(colNum - 1, value);
        this.flushStorage.setFloat(colNum - 1, value);
        return true;
    }

    @Override
    public boolean setDouble(int colNum, Double value) {
        this.flushValidator.validateSet(colNum - 1, value);
        this.flushStorage.setDouble(colNum - 1, value);
        return true;
    }

    @Override
    public boolean setBigDecimal(int colNum, BigDecimal value) {
        this.flushValidator.validateSet(colNum - 1, value);
        this.flushStorage.setBigDecimal(colNum - 1, value);
        return true;
    }

    @Override
    public boolean setVarchar(int colNum, String value) throws ConnException {
        try {
            this.flushValidator.validateSet(colNum - 1, value);
            byte[] stringBytes = value == null ? "".getBytes(this.varcharEncoding) : value.getBytes(this.varcharEncoding);
            this.flushValidator.validateVarchar(colNum - 1, stringBytes.length);
            this.flushStorage.setVarchar(colNum - 1, stringBytes, value);
            return true;
        }
        catch (UnsupportedEncodingException e) {
            throw new ConnException(e);
        }
    }

    @Override
    public boolean setNvarchar(int colNum, String value) throws ConnException {
        this.flushValidator.validateSet(colNum - 1, value);
        byte[] stringBytes = value == null ? "".getBytes(UTF8) : value.getBytes(UTF8);
        this.flushStorage.setNvarchar(colNum - 1, stringBytes, value);
        return true;
    }

    @Override
    public boolean setDate(int colNum, Date date, ZoneId zone) {
        this.flushValidator.validateSet(colNum - 1, date);
        this.flushStorage.setDate(colNum - 1, date, zone);
        return true;
    }

    @Override
    public boolean setDatetime(int colNum, Timestamp ts, ZoneId zone) {
        this.flushValidator.validateSet(colNum - 1, ts);
        this.flushStorage.setDatetime(colNum - 1, ts, zone);
        return true;
    }

    @Override
    public boolean setDate(int colNum, Date value) {
        return this.setDate(colNum, value, SYSTEM_TZ);
    }

    @Override
    public boolean setDatetime(int colNum, Timestamp value) {
        return this.setDatetime(colNum, value, SYSTEM_TZ);
    }

    @Override
    public boolean setOffsetDateTime(int col_num, OffsetDateTime value) throws ConnException {
        this.flushStorage.setOffsetDateTime(col_num - 1, value);
        return true;
    }

    @Override
    public boolean setInstant(int col_num, Instant value) throws ConnException {
        this.flushStorage.setInstant(col_num - 1, value);
        return true;
    }

    @Override
    public boolean setArray(int colNum, SQArrayGeneric array) throws SQLException, ConnException {
        this.flushStorage.setArray(colNum - 1, array);
        return true;
    }

    @Override
    public void setTimeout(int seconds) {
        this.timeout = seconds;
    }

    private int validateColNum(int colNum) throws ConnException {
        if (colNum < 1) {
            throw new ConnException("Using a metadata function with a non positive column value");
        }
        return --colNum;
    }

    @Override
    public int getStatementId() {
        return this.sqreamCreatedStatement.getId();
    }

    @Override
    public String getQueryType() {
        return this.sqreamExecutedStatement.getType().getValue();
    }

    @Override
    public int getRowLength() {
        return this.sqreamExecutedStatement.getActiveMetadata().getRowLength();
    }

    @Override
    public String getColName(int colNum) throws ConnException {
        return this.sqreamExecutedStatement.getActiveMetadata().getName(this.validateColNum(colNum));
    }

    @Override
    public String getColType(int colNum) throws ConnException {
        return this.sqreamExecutedStatement.getActiveMetadata().getType(this.validateColNum(colNum));
    }

    @Override
    public String getColType(String colName) throws ConnException {
        Integer colNum = this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName);
        if (colNum == null) {
            throw new ConnException("\nno column found for name: " + colName + "\nExisting columns: \n" + String.valueOf(this.sqreamExecutedStatement.getOutputMeta().getAllNames()));
        }
        return this.getColType(colNum);
    }

    @Override
    public String getColSubType(int colNum) throws ConnException {
        return this.sqreamExecutedStatement.getActiveMetadata().getSubType(this.validateColNum(colNum));
    }

    @Override
    public String getColSubType(String colName) throws ConnException {
        Integer colNum = this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName);
        if (colNum == null) {
            throw new ConnException("\nno sub column found for name: " + colName + "\nExisting columns: \n" + String.valueOf(this.sqreamExecutedStatement.getOutputMeta().getAllNames()));
        }
        return this.getColSubType(colNum);
    }

    @Override
    public int getColNum(String colName) throws ConnException {
        Integer colNum = this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName);
        if (colNum == null) {
            throw new ConnException("\nno column found for name: " + colName + "\nExisting columns: \n" + String.valueOf(this.sqreamExecutedStatement.getOutputMeta().getAllNames()));
        }
        return this.getColNum(colNum);
    }

    @Override
    public int getColNum(int colNum) throws ConnException {
        return this.sqreamExecutedStatement.getActiveMetadata().getColNum(this.validateColNum(colNum));
    }

    @Override
    public int getColSize(int colNum) throws ConnException {
        return this.sqreamExecutedStatement.getActiveMetadata().getSize(this.validateColNum(colNum));
    }

    @Override
    public int getColScale(int colNum) throws ConnException {
        return this.sqreamExecutedStatement.getActiveMetadata().getScale(this.validateColNum(colNum));
    }

    @Override
    public int getColScale(String colName) throws ConnException {
        Integer colNum = this.sqreamExecutedStatement.getActiveMetadata().getColNumByName(colName);
        return this.getColScale(colNum);
    }

    @Override
    public int getColPrecision(int colNum) throws ConnException {
        return this.sqreamExecutedStatement.getActiveMetadata().getPrecision(this.validateColNum(colNum));
    }

    @Override
    public boolean isColNullable(int colNum) throws ConnException {
        return this.sqreamExecutedStatement.getActiveMetadata().isNullable(this.validateColNum(colNum));
    }

    @Override
    public boolean isOpenStatement() {
        return this.sqreamCreatedStatement != null && this.sqreamCreatedStatement.isOpen();
    }

    @Override
    public boolean isOpen() {
        return this.sqreamConnection != null && this.sqreamConnection.isOpen();
    }

    @Override
    public AtomicBoolean checkCancelStatement() {
        return this.IsCancelStatement;
    }

    @Override
    public boolean setFetchLimit(int fetchLimit) throws ConnException {
        if (fetchLimit < 0) {
            throw new ConnException(MessageFormat.format("Max rows [{0}] to fetch should be non negative", fetchLimit));
        }
        this.fetchLimit = fetchLimit;
        return true;
    }

    @Override
    public int getFetchLimit() {
        return this.fetchLimit;
    }

    @Override
    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    @Override
    public int getFetchSize() {
        return this.fetchSize;
    }

    @Override
    public String getServerVersion() {
        return this.sqreamConnection.getServerVersion();
    }

    @Override
    public String getDatabase() {
        return this.database;
    }

    private ByteBufferPool createByteBufferPool(int blockSize, StatementType statementType) throws ConnException {
        int poolSize = statementType.equals((Object)StatementType.PARAMED_INSERT) ? 3 : 1;
        try {
            return new ByteBufferPool(poolSize, blockSize, this.sqreamExecutedStatement.getInputMeta());
        }
        catch (OutOfMemoryError error) {
            try {
                this.closeConnection();
                long maxMemory = Runtime.getRuntime().maxMemory() / 0x100000L;
                LOGGER.log(Level.FINE, MessageFormat.format("Not enough heap memory [{0} Mb] to process", maxMemory));
                throw new OutOfMemoryError(MessageFormat.format("Not enough heap memory [{0} Mb] to process", maxMemory));
            }
            catch (ConnException e) {
                throw new ConnException(e);
            }
        }
    }
}

