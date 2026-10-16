package com.dadp.jdbc;

import com.dadp.common.sync.policy.PolicyResolver;
import java.sql.*;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EngineStatementGuardTest {
    @Test void batchQueuedBeforePolicyArrivalCannotExecute() throws Exception {
        Statement raw = mock(Statement.class);
        DadpProxyConnection connection = mock(DadpProxyConnection.class);
        PolicyResolver policies = mock(PolicyResolver.class);
        when(connection.getPolicyResolver()).thenReturn(policies);
        when(policies.getAllMappings()).thenReturn(Collections.emptyMap());
        DadpProxyStatement statement = new DadpProxyStatement(raw, connection);
        statement.addBatch("INSERT INTO users(secret) VALUES ('plain')");
        when(policies.getAllMappings()).thenReturn(Collections.singletonMap("users.secret", "ABCD2345"));
        assertThrows(SQLFeatureNotSupportedException.class, statement::executeBatch);
        assertThrows(SQLFeatureNotSupportedException.class, statement::executeLargeBatch);
        verify(raw, never()).executeBatch(); verify(raw, never()).executeLargeBatch();
    }

    @Test void literalWritesAndBatchFailBeforeNativeExecutionWithActivePolicies() throws Exception {
        Statement raw = mock(Statement.class);
        DadpProxyConnection connection = mock(DadpProxyConnection.class);
        PolicyResolver policies = mock(PolicyResolver.class);
        when(connection.getPolicyResolver()).thenReturn(policies);
        when(policies.getAllMappings()).thenReturn(Collections.singletonMap("users.secret", "ABCD2345"));
        DadpProxyStatement statement = new DadpProxyStatement(raw, connection);
        String sql = "INSERT INTO users(secret) VALUES ('plain')";
        assertThrows(SQLFeatureNotSupportedException.class, () -> statement.execute(sql));
        assertThrows(SQLFeatureNotSupportedException.class, () -> statement.executeUpdate(sql));
        assertThrows(SQLFeatureNotSupportedException.class, () -> statement.executeLargeUpdate(sql));
        assertThrows(SQLFeatureNotSupportedException.class, () -> statement.addBatch(sql));
        assertThrows(SQLFeatureNotSupportedException.class, () -> statement.executeQuery(sql));
        assertThrows(SQLFeatureNotSupportedException.class, () -> statement.execute("SELECT 1; " + sql));
        assertThrows(SQLFeatureNotSupportedException.class, () -> statement.execute("SELECT secret INTO copy FROM users"));
        verifyNoInteractions(raw);
        statement.executeQuery("SELECT secret FROM users");
        verify(raw).executeQuery("SELECT secret FROM users");
    }
}
