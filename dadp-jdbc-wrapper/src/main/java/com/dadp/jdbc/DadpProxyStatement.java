package com.dadp.jdbc;

import java.sql.*;
import java.util.Locale;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

/**
 * DADP Proxy Statement.
 *
 * Wraps a JDBC Statement and preserves wrapper-side result-set handling.
 * 
 * @author DADP Development Team
 * @version 3.0.5
 * @since 2025-11-26
 */
public class DadpProxyStatement implements Statement {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(DadpProxyStatement.class);
    
    private final Statement actualStatement;
    private final DadpProxyConnection proxyConnection;
    private String lastResultSetSql;
    
    public DadpProxyStatement(Statement actualStatement, DadpProxyConnection proxyConnection) {
        this.actualStatement = actualStatement;
        this.proxyConnection = proxyConnection;
        log.trace("DADP Proxy Statement created");
    }
    
    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        long start = System.nanoTime();
        boolean error = false;
        try {
            log.debug("Statement.executeQuery executed: {}", sql);
            ResultSet actualRs = actualStatement.executeQuery(sql);
            lastResultSetSql = sql;
            return wrapResultSet(actualRs, sql);
        } catch (SQLException e) {
            error = true;
            throw e;
        } finally {
            recordTelemetry(sql, start, error);
        }
    }
    
    @Override
    public int executeUpdate(String sql) throws SQLException {
        return executeWithTelemetry(sql, () -> actualStatement.executeUpdate(sql), false);
    }
    
    @Override
    public void close() throws SQLException {
        actualStatement.close();
    }
    
    @Override
    public int getMaxFieldSize() throws SQLException {
        return actualStatement.getMaxFieldSize();
    }
    
    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        actualStatement.setMaxFieldSize(max);
    }
    
    @Override
    public int getMaxRows() throws SQLException {
        return actualStatement.getMaxRows();
    }
    
    @Override
    public void setMaxRows(int max) throws SQLException {
        actualStatement.setMaxRows(max);
    }
    
    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        actualStatement.setEscapeProcessing(enable);
    }
    
    @Override
    public int getQueryTimeout() throws SQLException {
        return actualStatement.getQueryTimeout();
    }
    
    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        actualStatement.setQueryTimeout(seconds);
    }
    
    @Override
    public void cancel() throws SQLException {
        actualStatement.cancel();
    }
    
    @Override
    public SQLWarning getWarnings() throws SQLException {
        return actualStatement.getWarnings();
    }
    
    @Override
    public void clearWarnings() throws SQLException {
        actualStatement.clearWarnings();
    }
    
    @Override
    public void setCursorName(String name) throws SQLException {
        actualStatement.setCursorName(name);
    }
    
    @Override
    public boolean execute(String sql) throws SQLException {
        return executeWithTelemetry(sql, () -> actualStatement.execute(sql), true);
    }
    
    @Override
    public ResultSet getResultSet() throws SQLException {
        return wrapResultSet(actualStatement.getResultSet(), lastResultSetSql);
    }
    
    @Override
    public int getUpdateCount() throws SQLException {
        return actualStatement.getUpdateCount();
    }
    
    @Override
    public boolean getMoreResults() throws SQLException {
        return actualStatement.getMoreResults();
    }
    
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        actualStatement.setFetchDirection(direction);
    }
    
    @Override
    public int getFetchDirection() throws SQLException {
        return actualStatement.getFetchDirection();
    }
    
    @Override
    public void setFetchSize(int rows) throws SQLException {
        actualStatement.setFetchSize(rows);
    }
    
    @Override
    public int getFetchSize() throws SQLException {
        return actualStatement.getFetchSize();
    }
    
    @Override
    public int getResultSetConcurrency() throws SQLException {
        return actualStatement.getResultSetConcurrency();
    }
    
    @Override
    public int getResultSetType() throws SQLException {
        return actualStatement.getResultSetType();
    }
    
    @Override
    public void addBatch(String sql) throws SQLException {
        actualStatement.addBatch(sql);
    }
    
    @Override
    public void clearBatch() throws SQLException {
        actualStatement.clearBatch();
    }
    
    @Override
    public int[] executeBatch() throws SQLException {
        return actualStatement.executeBatch();
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        return proxyConnection;
    }
    
    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return actualStatement.getMoreResults(current);
    }
    
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return actualStatement.getGeneratedKeys();
    }
    
    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeWithTelemetry(sql, () -> actualStatement.executeUpdate(sql, autoGeneratedKeys), false);
    }
    
    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeWithTelemetry(sql, () -> actualStatement.executeUpdate(sql, columnIndexes), false);
    }
    
    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeWithTelemetry(sql, () -> actualStatement.executeUpdate(sql, columnNames), false);
    }
    
    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return executeWithTelemetry(sql, () -> actualStatement.execute(sql, autoGeneratedKeys), true);
    }
    
    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return executeWithTelemetry(sql, () -> actualStatement.execute(sql, columnIndexes), true);
    }
    
    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return executeWithTelemetry(sql, () -> actualStatement.execute(sql, columnNames), true);
    }
    
    @Override
    public int getResultSetHoldability() throws SQLException {
        return actualStatement.getResultSetHoldability();
    }
    
    @Override
    public boolean isClosed() throws SQLException {
        return actualStatement.isClosed();
    }
    
    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        actualStatement.setPoolable(poolable);
    }
    
    @Override
    public boolean isPoolable() throws SQLException {
        return actualStatement.isPoolable();
    }
    
    @Override
    public void closeOnCompletion() throws SQLException {
        actualStatement.closeOnCompletion();
    }
    
    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return actualStatement.isCloseOnCompletion();
    }
    
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return actualStatement.unwrap(iface);
    }
    
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || actualStatement.isWrapperFor(iface);
    }

    private ResultSet wrapResultSet(ResultSet actualRs, String resultSetSql) throws SQLException {
        if (actualRs != null) {
            return new DadpProxyResultSet(actualRs, resultSetSql, proxyConnection);
        }
        return null;
    }

    private <T> T executeWithTelemetry(String sql, SqlCallable<T> action, boolean captureResultSetSql)
            throws SQLException {
        long start = System.nanoTime();
        boolean error = false;
        try {
            T result = action.call();
            if (captureResultSetSql) {
                boolean hasResultSet = result instanceof Boolean && ((Boolean) result).booleanValue();
                lastResultSetSql = hasResultSet ? sql : null;
            } else {
                lastResultSetSql = null;
            }
            return result;
        } catch (SQLException e) {
            error = true;
            throw e;
        } finally {
            recordTelemetry(sql, start, error);
        }
    }

    private void recordTelemetry(String sql, long startNanos, boolean errorFlag) {
        if (sql == null || sql.trim().isEmpty()) {
            return;
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        proxyConnection.sendSqlTelemetry(sql, extractSqlType(sql), durationMs, errorFlag);
    }

    private String extractSqlType(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        if (trimmed.isEmpty()) {
            return "UNKNOWN";
        }
        int delimiter = trimmed.indexOf(' ');
        String firstToken = delimiter >= 0 ? trimmed.substring(0, delimiter) : trimmed;
        return firstToken.toUpperCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface SqlCallable<T> {
        T call() throws SQLException;
    }
}
