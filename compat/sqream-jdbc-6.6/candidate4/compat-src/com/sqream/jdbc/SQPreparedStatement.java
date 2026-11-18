/*
 * Decompiled with CFR 0.152.
 */
package com.sqream.jdbc;

import com.sqream.jdbc.ConnectionParams;
import com.sqream.jdbc.SQConnection;
import com.sqream.jdbc.SQLoggablePreparedStatement;
import com.sqream.jdbc.SQParameterMetaData;
import com.sqream.jdbc.SQResultSet;
import com.sqream.jdbc.SQResultSetMetaData;
import com.sqream.jdbc.connector.ConnException;
import com.sqream.jdbc.connector.ConnectorImpl;
import com.sqream.jdbc.connector.SQArrayGeneric;
import com.sqream.jdbc.connector.enums.StatementType;
import com.sqream.jdbc.utils.Utils;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SQPreparedStatement
implements PreparedStatement {
    private static final Logger LOGGER = Logger.getLogger(SQPreparedStatement.class.getName());
    private ConnectorImpl client;
    private SQResultSet SQRS = null;
    private SQResultSetMetaData metaData = null;
    private int statementId;
    private String db_name;
    private int setCounter = 0;
    private List<Integer> setsPerBatch = new ArrayList<Integer>();
    private boolean isClosed = true;
    private SQConnection connection;
    private AtomicBoolean IsCancelStatement = new AtomicBoolean(false);

    protected SQPreparedStatement(SQConnection conn, String sql, ConnectionParams connParams) throws ConnException {
        LOGGER.log(Level.FINE, MessageFormat.format("Construct SQPreparedStatement for [{0}]", sql));
        this.connection = conn;
        this.db_name = connParams.getDbName();
        this.isClosed = false;
        this.client = new ConnectorImpl(connParams, true);
        this.client.connect(connParams.getDbName(), connParams.getUser(), connParams.getPassword(), connParams.getService());
        if (connParams.getFetchSize() != null && connParams.getFetchSize() > 0) {
            this.client.setFetchSize(connParams.getFetchSize());
        }
        this.connection.getStatements().add(this);
        this.statementId = this.client.execute(sql).getStatementId();
        this.metaData = new SQResultSetMetaData(this.client, connParams.getDbName());
    }

    static SQPreparedStatement getInstance(SQConnection conn, String sql, ConnectionParams connParams) throws ConnException {
        if (LOGGER.isLoggable(Level.FINE)) {
            return new SQLoggablePreparedStatement(conn, sql, connParams);
        }
        return new SQPreparedStatement(conn, sql, connParams);
    }

    @Override
    public void close() throws SQLException {
        if (this.isClosed) return;
        Throwable failure = null;
        try {
            if (this.client != null) this.client.closeConnection();
        } catch (Throwable primary) { failure = primary; }
        finally {
            try {
                if (this.connection != null) this.connection.removeItem(this);
            } catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else if (cleanup != failure) failure.addSuppressed(cleanup);
            } finally { this.isClosed = true; }
        }
        if (failure instanceof Error) throw (Error)failure;
        if (failure instanceof SQLException) throw (SQLException)failure;
        if (failure != null) throw new SQLException(failure);
    }

    private long compatAckBeforeExecution() throws SQLException {
        try { return this.client.compatStatementAckSequence(); }
        catch (ConnException failure) { throw new SQLException(failure); }
    }

    private void compatRequireNewCloseAck(long priorSequence) throws SQLException {
        try { this.client.compatRequireStatementCloseAck(this.statementId, priorSequence); }
        catch (ConnException failure) { throw new SQLException(failure); }
    }

    @Override
    public int[] executeBatch() throws SQLException {
        if (this.isClosed) throw new SQLException("Statement is closed", "07000");
        if (this.setsPerBatch.isEmpty()) return new int[0];
        if (!this.client.isOpenStatement())
            throw new SQLException("SQream native statement has completed; prepare a new statement", "07000");
        if (this.setCounter > 0)
            throw new SQLException("Add the current bound row with addBatch before executeBatch", "07001");
        String type = this.client.getQueryType();
        if (!StatementType.PARAMED_INSERT.getValue().equals(type)
                && !StatementType.PARAMED_UPDATE_DELETE.getValue().equals(type))
            throw new SQLException("executeBatch requires a parameterized non-SELECT statement", "07000");
        int rows = this.setsPerBatch.size();
        long priorAck = this.compatAckBeforeExecution();
        this.sendParameters(); // setCounter==0: do not append an already-added row again.
        try { this.client.close(); }
        catch (Exception failure) { throw new SQLException(failure); }
        this.compatRequireNewCloseAck(priorAck);
        int[] result = new int[rows];
        Arrays.fill(result, 1); // Preserve the native driver's per-added-row count convention.
        this.setsPerBatch.clear(); // Only after PUT and statement-close ACK.
        return result;
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        return this.SQRS;
    }

    @Override
    public void addBatch() throws SQLException {
        if (this.isClosed || !this.client.isOpenStatement())
            throw new SQLException("SQream native statement has completed; prepare a new statement", "07000");
        try {
            this.client.next();
            this.setsPerBatch.add(this.setCounter);
            this.setCounter = 0;
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
    }

    @Override
    public boolean execute() throws SQLException {
        if (this.isClosed || !this.client.isOpenStatement())
            throw new SQLException("SQream native statement has completed; prepare a new statement", "07000");
        String type = this.client.getQueryType();
        if (StatementType.PARAMED_INSERT.getValue().equals(type)
                || StatementType.PARAMED_UPDATE_DELETE.getValue().equals(type)) {
            this.executeUpdateInternal();
            this.setsPerBatch.clear();
            this.SQRS = null;
            return false;
        }
        if (StatementType.PARAMED_SELECT.getValue().equals(type)
                || StatementType.QUERY.getValue().equals(type)) {
            this.executeQuery();
            return true;
        }
        long priorAck = this.compatAckBeforeExecution();
        try { this.client.close(); }
        catch (Exception failure) { throw new SQLException(failure); }
        this.compatRequireNewCloseAck(priorAck);
        this.SQRS = null;
        return false;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        if (this.client.getQueryType().equals(StatementType.PARAMED_SELECT.getValue())) {
            this.sendParameters();
            try {
                this.client.initializeFetch();
            }
            catch (ConnException e) {
                throw new SQLException(e);
            }
        } else if (!this.client.getQueryType().equals(StatementType.QUERY.getValue())) {
            throw new SQLException("Cannot execute a non-SELECT or non-parameterized SELECT statement with executeQuery(). Use execute() instead.");
        }
        this.SQRS = SQResultSet.getInstance(this.client, this.db_name);
        return this.SQRS;
    }

    public void sendParameters() throws SQLException {
        try {
            if (this.setCounter > 0) {
                this.client.flushInitial();
                this.setsPerBatch.add(this.setCounter);
                this.setCounter = 0;
            }
            this.client.flushAndClose();
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
    }

    @Override
    public void setBoolean(int colNum, boolean value) throws SQLException {
        try {
            this.client.setBoolean(colNum, value);
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    @Override
    public void setByte(int colNum, byte value) throws SQLException {
        try {
            this.client.setUbyte(colNum, value);
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    @Override
    public void setShort(int colNum, short value) throws SQLException {
        try {
            this.client.setShort(colNum, value);
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    @Override
    public void setInt(int colNum, int value) throws SQLException {
        try {
            this.client.setInt(colNum, value);
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    @Override
    public void setLong(int colNum, long value) throws SQLException {
        try {
            this.client.setLong(colNum, value);
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    @Override
    public void setFloat(int colNum, float value) throws SQLException {
        try {
            this.client.setFloat(colNum, Float.valueOf(value));
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    @Override
    public void setDouble(int colNum, double value) throws SQLException {
        try {
            this.client.setDouble(colNum, value);
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    @Override
    public void setBigDecimal(int colNum, BigDecimal value) throws SQLException {
        try {
            this.client.setBigDecimal(colNum, value);
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    @Override
    public void setDate(int colNum, Date date) throws SQLException {
        try {
            this.client.setDate(colNum, date);
        }
        catch (Exception e) {
            throw new SQLException(e.getMessage());
        }
        ++this.setCounter;
    }

    @Override
    public void setDate(int colNum, Date date, Calendar cal) throws SQLException {
        try {
            this.client.setDate(colNum, date, cal.getTimeZone().toZoneId());
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    @Override
    public void setTimestamp(int colNum, Timestamp datetime) throws SQLException {
        try {
            this.client.setDatetime(colNum, datetime);
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    @Override
    public void setTimestamp(int colNum, Timestamp datetime, Calendar cal) throws SQLException {
        try {
            this.client.setDatetime(colNum, datetime, cal.getTimeZone().toZoneId());
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    public void setOffsetDateTime(int colNum, OffsetDateTime datetime) throws SQLException {
        try {
            this.client.setOffsetDateTime(colNum, datetime);
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    public void setInstant(int colNum, Instant instant) throws SQLException {
        try {
            this.client.setInstant(colNum, instant);
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    @Override
    public void setString(int colNum, String value) throws SQLException {
        block4: {
            String type = this.getMetaData().getColumnTypeName(colNum);
            try {
                if ("Varchar".equals(type)) {
                    this.client.setVarchar(colNum, value);
                    break block4;
                }
                if ("NVarchar".equals(type)) {
                    this.client.setNvarchar(colNum, value);
                    break block4;
                }
                throw new IllegalArgumentException(MessageFormat.format("Trying to set string on a column number [{0}] of type [{1}]", colNum, type));
            }
            catch (Exception e) {
                throw new SQLException(e);
            }
        }
        ++this.setCounter;
    }

    @Override
    public void setNString(int arg0, String arg1) throws SQLException {
        try {
            this.client.setNvarchar(arg0, arg1);
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        ++this.setCounter;
    }

    @Override
    public void setNull(int arg0, int arg1) throws SQLException {
        String type = "";
        try {
            switch (type = this.client.getColType(arg0)) {
                case "ftBool": {
                    this.client.setBoolean(arg0, null);
                    break;
                }
                case "ftUByte": {
                    this.client.setUbyte(arg0, null);
                    break;
                }
                case "ftShort": {
                    this.client.setShort(arg0, null);
                    break;
                }
                case "ftInt": {
                    this.client.setInt(arg0, null);
                    break;
                }
                case "ftLong": {
                    this.client.setLong(arg0, null);
                    break;
                }
                case "ftFloat": {
                    this.client.setFloat(arg0, null);
                    break;
                }
                case "ftDouble": {
                    this.client.setDouble(arg0, null);
                    break;
                }
                case "ftDate": {
                    this.client.setDate(arg0, null);
                    break;
                }
                case "ftDateTime": {
                    this.client.setDatetime(arg0, null);
                    break;
                }
                case "ftVarchar": {
                    this.client.setVarchar(arg0, null);
                    break;
                }
                case "ftBlob": {
                    this.client.setNvarchar(arg0, null);
                    break;
                }
                case "ftNumeric": {
                    this.client.setBigDecimal(arg0, null);
                    break;
                }
                case "ftTimestampTz": {
                    this.client.setOffsetDateTime(arg0, null);
                    break;
                }
                case "ftUnixTimestamp": {
                    this.client.setInstant(arg0, null);
                    break;
                }
                case "ftArray": {
                    this.client.setArray(arg0, null);
                    break;
                }
                default: {
                    throw new IllegalArgumentException(MessageFormat.format("Unsupported column type [{0}]", type));
                }
            }
            ++this.setCounter;
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
    }

    @Override
    public void setObject(int colNum, Object value) throws SQLException {
        if (value instanceof Boolean) {
            this.setBoolean(colNum, (Boolean)value);
        } else if (value instanceof Byte) {
            this.setByte(colNum, (Byte)value);
        } else if (value instanceof Short) {
            this.setShort(colNum, (Short)value);
        } else if (value instanceof Integer) {
            this.setInt(colNum, (Integer)value);
        } else if (value instanceof Long) {
            this.setLong(colNum, (Long)value);
        } else if (value instanceof Float) {
            this.setFloat(colNum, ((Float)value).floatValue());
        } else if (value instanceof Double) {
            this.setDouble(colNum, (Double)value);
        } else if (value instanceof Date) {
            this.setDate(colNum, (Date)value);
        } else if (value instanceof Timestamp) {
            this.setTimestamp(colNum, (Timestamp)value);
        } else if (value instanceof String) {
            this.setString(colNum, (String)value);
        } else if (value instanceof BigDecimal) {
            this.setBigDecimal(colNum, (BigDecimal)value);
        } else if (value instanceof OffsetDateTime) {
            this.setOffsetDateTime(colNum, (OffsetDateTime)value);
        } else if (value instanceof Instant) {
            this.setInstant(colNum, (Instant)value);
        } else if (value instanceof Array) {
            this.setArray(colNum, (Array)value);
        } else {
            throw new SQLException(MessageFormat.format("Type [{0}] for setObject not supported", value != null ? value.getClass().getName() : null));
        }
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        if (this.metaData == null) {
            throw new SQLException("MetaData is empty");
        }
        return this.metaData;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        if (rows < 0) {
            throw new SQLException("setFetchSize argument should be nonnegative, got " + rows);
        }
    }

    @Override
    public boolean execute(String arg0) throws SQLException {
        throw new SQLException("Method is called on a PreparedStatement");
    }

    @Override
    public boolean execute(String arg0, int arg1) throws SQLException {
        throw new SQLException("Method is called on a PreparedStatement");
    }

    @Override
    public boolean execute(String arg0, int[] arg1) throws SQLException {
        throw new SQLException("Method is called on a PreparedStatement");
    }

    @Override
    public boolean execute(String arg0, String[] arg1) throws SQLException {
        throw new SQLException("Method is called on a PreparedStatement");
    }

    @Override
    public int executeUpdate(String arg0) throws SQLException {
        throw new SQLException("Method is called on a PreparedStatement");
    }

    @Override
    public int executeUpdate(String arg0, int arg1) throws SQLException {
        throw new SQLException("Method is called on a PreparedStatement");
    }

    @Override
    public int executeUpdate(String arg0, int[] arg1) throws SQLException {
        throw new SQLException("Method is called on a PreparedStatement");
    }

    @Override
    public int executeUpdate(String arg0, String[] arg1) throws SQLException {
        throw new SQLException("Method is called on a PreparedStatement");
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return 0;
    }

    @Override
    public int getFetchSize() throws SQLException {
        return this.client.getFetchSize();
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxRows() throws SQLException {
        return this.client.getFetchLimit();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return false;
    }

    @Override
    public boolean getMoreResults(int arg0) throws SQLException {
        return false;
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        if (this.isClosed) {
            throw new SQLException("Called getQueryTimeout() on closed statement");
        }
        return this.client.getTimeout();
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return 0;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return 0;
    }

    @Override
    public int getResultSetType() throws SQLException {
        return 0;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return 0;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return this.isClosed;
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return false;
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return false;
    }

    @Override
    public void cancel() throws SQLException {
        this.client.cancel(this.connection);
    }

    @Override
    public void clearBatch() throws SQLException {
    }

    @Override
    public void clearWarnings() throws SQLException {
        if (this.isClosed) {
            throw new SQLException("Call clearWarnings() on closed PreparedStatement");
        }
    }

    @Override
    public void setNull(int arg0, int arg1, String arg2) throws SQLException {
    }

    @Override
    public boolean isWrapperFor(Class<?> arg0) throws SQLException {
        return false;
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return new SQParameterMetaData(this.client);
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        if (this.isClosed) {
            throw new SQLException("Called setQueryTimeout() on closed statement");
        }
        if (seconds < 0) {
            throw new SQLException(MessageFormat.format("Query timeout [{0}] must be a value greater than or equals to 0.", seconds));
        }
        this.client.setTimeout(seconds);
    }

    @Override
    public void setMaxRows(int maxRows) throws SQLException {
        try {
            this.client.setFetchLimit(maxRows);
        }
        catch (Exception e) {
            throw new SQLException("Error in setMaxRows:" + String.valueOf(e));
        }
    }

    @Override
    public void addBatch(String arg0) throws SQLException {
        throw new SQLFeatureNotSupportedException("addBatch in SQPreparedStatement");
    }

    @Override
    public ResultSet executeQuery(String arg0) throws SQLException {
        throw new SQLFeatureNotSupportedException("executeQuery in SQPreparedStatement");
    }

    @Override
    public Connection getConnection() throws SQLException {
        throw new SQLFeatureNotSupportedException("getConnection in SQPreparedStatement");
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        throw new SQLFeatureNotSupportedException("getGeneratedKeys in SQPreparedStatement");
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        if (this.isClosed) {
            throw new SQLException("Call getWarnings() on closed PreparedStatement");
        }
        return null;
    }

    @Override
    public void setCursorName(String arg0) throws SQLException {
        throw new SQLFeatureNotSupportedException("setCursorName in SQPreparedStatement");
    }

    @Override
    public void setEscapeProcessing(boolean arg0) throws SQLException {
        throw new SQLFeatureNotSupportedException("setEscapeProcessing in SQPreparedStatement");
    }

    @Override
    public void setFetchDirection(int arg0) throws SQLException {
        throw new SQLFeatureNotSupportedException("setFetchDirection in SQPreparedStatement");
    }

    @Override
    public void setMaxFieldSize(int arg0) throws SQLException {
        throw new SQLFeatureNotSupportedException("setMaxFieldSize in SQPreparedStatement");
    }

    @Override
    public void setPoolable(boolean arg0) throws SQLException {
        throw new SQLFeatureNotSupportedException("setPoolable in SQPreparedStatement");
    }

    @Override
    public <T> T unwrap(Class<T> arg0) throws SQLException {
        throw new SQLFeatureNotSupportedException("unwrap in SQPreparedStatement");
    }

    @Override
    public void clearParameters() throws SQLException {
        throw new SQLFeatureNotSupportedException("clearParameters in SQPreparedStatement");
    }

    @Override
    public int executeUpdate() throws SQLException {
        long rows = this.executeUpdateInternal();
        if (Utils.isOutOfIntRange(rows)) {
            throw new SQLException("Updated rows count '" + rows + "' exceeds the maximum value for int. Use executeLargeUpdate instead.");
        }
        return (int)rows;
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        return this.executeUpdateInternal();
    }

    private long executeUpdateInternal() throws SQLException {
        if (this.isClosed) {
            throw new SQLException("Called executeUpdate() on closed statement");
        }
        if (!this.client.isOpenStatement())
            throw new SQLException("SQream native statement has completed; prepare a new statement", "07000");
        String queryType = this.client.getQueryType();
        if (queryType.equals(StatementType.PARAMED_SELECT.getValue()) || queryType.equals(StatementType.QUERY.getValue())) {
            throw new SQLException("executeUpdate() cannot be called with a SELECT statement");
        }
        long priorAck = this.compatAckBeforeExecution();
        this.sendParameters();
        try {
            this.client.close();
        }
        catch (Exception e) {
            throw new SQLException(e);
        }
        this.compatRequireNewCloseAck(priorAck);
        return this.setsPerBatch.size();
    }

    @Override
    public void setArray(int colNum, Array array) throws SQLException {
        if (array instanceof SQArrayGeneric || array == null) {
            SQArrayGeneric sqArray = (SQArrayGeneric)array;
            try {
                this.client.setArray(colNum, sqArray);
                ++this.setCounter;
            }
            catch (Exception e) {
                throw new SQLException(e.getMessage());
            }
        } else {
            String className = array.getClass().getName();
            try {
                throw new Exception(MessageFormat.format("instanceof array is not on the correct object : object=[SQArray], array=[{0}]", className));
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setAsciiStream(int arg0, InputStream arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setAsciiStream in SQPreparedStatement");
    }

    @Override
    public void setAsciiStream(int arg0, InputStream arg1, int arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setAsciiStream 2 in SQPreparedStatement");
    }

    @Override
    public void setAsciiStream(int arg0, InputStream arg1, long arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setAsciiStream 3 in SQPreparedStatement");
    }

    @Override
    public void setBinaryStream(int arg0, InputStream arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBinaryStream in SQPreparedStatement");
    }

    @Override
    public void setBinaryStream(int arg0, InputStream arg1, int arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBinaryStream 2 in SQPreparedStatement");
    }

    @Override
    public void setBinaryStream(int arg0, InputStream arg1, long arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBinaryStream 3 in SQPreparedStatement");
    }

    @Override
    public void setBlob(int arg0, Blob arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBlob in SQPreparedStatement");
    }

    @Override
    public void setBlob(int arg0, InputStream arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBlob 2 in SQPreparedStatement");
    }

    @Override
    public void setBlob(int arg0, InputStream arg1, long arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBlob 3 in SQPreparedStatement");
    }

    @Override
    public void setBytes(int arg0, byte[] arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBytes in SQPreparedStatement");
    }

    @Override
    public void setCharacterStream(int arg0, Reader arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setCharacterStream in SQPreparedStatement");
    }

    @Override
    public void setCharacterStream(int arg0, Reader arg1, int arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setCharacterStream 2 in SQPreparedStatement");
    }

    @Override
    public void setCharacterStream(int arg0, Reader arg1, long arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setCharacterStream 3 in SQPreparedStatement");
    }

    @Override
    public void setClob(int arg0, Clob arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setClob in SQPreparedStatement");
    }

    @Override
    public void setClob(int arg0, Reader arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setClob 2 in SQPreparedStatement");
    }

    @Override
    public void setClob(int arg0, Reader arg1, long arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setClob 3 in SQPreparedStatement");
    }

    @Override
    public void setNCharacterStream(int arg0, Reader arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setNCharacterStream in SQPreparedStatement");
    }

    @Override
    public void setNCharacterStream(int arg0, Reader arg1, long arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setNCharacterStream 2 in SQPreparedStatement");
    }

    @Override
    public void setNClob(int arg0, NClob arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setNClob in SQPreparedStatement");
    }

    @Override
    public void setNClob(int arg0, Reader arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setNClob in SQPreparedStatement");
    }

    @Override
    public void setNClob(int arg0, Reader arg1, long arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setNClob in SQPreparedStatement");
    }

    @Override
    public void setObject(int arg0, Object arg1, int arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setObject in SQPreparedStatement");
    }

    @Override
    public void setObject(int arg0, Object arg1, int arg2, int arg3) throws SQLException {
        throw new SQLFeatureNotSupportedException("setObject 2 in SQPreparedStatement");
    }

    @Override
    public void setRef(int arg0, Ref arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setRef in SQPreparedStatement");
    }

    @Override
    public void setRowId(int arg0, RowId arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setRowId in SQPreparedStatement");
    }

    @Override
    public void setSQLXML(int arg0, SQLXML arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setSQLXML in SQPreparedStatement");
    }

    @Override
    public void setTime(int arg0, Time arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setTime in SQPreparedStatement");
    }

    @Override
    public void setTime(int arg0, Time arg1, Calendar arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setTime 2 in SQPreparedStatement");
    }

    @Override
    public void setURL(int arg0, URL arg1) throws SQLException {
        throw new SQLFeatureNotSupportedException("setURL in SQPreparedStatement");
    }

    @Override
    public void setUnicodeStream(int arg0, InputStream arg1, int arg2) throws SQLException {
        throw new SQLFeatureNotSupportedException("setUnicodeStream in SQPreparedStatement");
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        throw new SQLFeatureNotSupportedException("closeOnCompletion in SQPreparedStatement");
    }
}

