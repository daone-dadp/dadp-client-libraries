package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class SqlTelemetryCoverageTest {

    @Test
    void statementExecuteQuerySendsSqlTelemetry() throws Exception {
        Statement actualStatement = mock(Statement.class);
        DadpProxyConnection proxyConnection = mock(DadpProxyConnection.class);
        ResultSet actualResultSet = mock(ResultSet.class);
        String sql = "SELECT * FROM users WHERE id = 1";

        when(actualStatement.executeQuery(sql)).thenReturn(actualResultSet);

        DadpProxyStatement proxyStatement = new DadpProxyStatement(actualStatement, proxyConnection);
        ResultSet wrapped = proxyStatement.executeQuery(sql);

        assertNotNull(wrapped);
        assertTrue(wrapped instanceof DadpProxyResultSet);
        verify(proxyConnection).sendSqlTelemetry(
                eq(sql),
                eq("SELECT"),
                longThat(duration -> duration >= 0L),
                eq(false));
    }

    @Test
    void preparedStatementExecuteSendsSqlTelemetry() throws Exception {
        PreparedStatement actualPreparedStatement = mock(PreparedStatement.class);
        DadpProxyConnection proxyConnection = mock(DadpProxyConnection.class);
        String sql = "SELECT * FROM users WHERE id = ?";

        when(actualPreparedStatement.execute()).thenReturn(true);

        DadpProxyPreparedStatement proxyPreparedStatement =
                new DadpProxyPreparedStatement(actualPreparedStatement, sql, proxyConnection);

        boolean executed = proxyPreparedStatement.execute();

        assertTrue(executed);
        verify(proxyConnection).sendSqlTelemetry(
                eq(sql),
                eq("SELECT"),
                longThat(duration -> duration >= 0L),
                eq(false));
    }

    @Test
    void preparedStatementStatementStyleExecuteUpdateSendsSqlTelemetry() throws Exception {
        PreparedStatement actualPreparedStatement = mock(PreparedStatement.class);
        DadpProxyConnection proxyConnection = mock(DadpProxyConnection.class);
        String baseSql = "SELECT 1";
        String updateSql = "UPDATE users SET name = 'neo' WHERE id = 7";

        when(actualPreparedStatement.executeUpdate(updateSql)).thenReturn(1);

        DadpProxyPreparedStatement proxyPreparedStatement =
                new DadpProxyPreparedStatement(actualPreparedStatement, baseSql, proxyConnection);

        proxyPreparedStatement.executeUpdate(updateSql);

        verify(proxyConnection).sendSqlTelemetry(
                eq(updateSql),
                eq("UPDATE"),
                longThat(duration -> duration >= 0L),
                eq(false));
    }
}
