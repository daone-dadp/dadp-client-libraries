package com.dadp.jdbc;

import com.dadp.common.sync.policy.PolicyResolver;
import java.io.*;
import java.sql.*;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnginePreparedSqlGuardTest {
    @Test void updatableResultSetsRejectedBeforeDelegateEvenWithoutMappings() throws Exception {
        DadpProxyConnection c = mock(DadpProxyConnection.class, CALLS_REAL_METHODS);
        Connection raw = mock(Connection.class);
        java.lang.reflect.Field field = DadpProxyConnection.class.getDeclaredField("actualConnection");
        field.setAccessible(true); field.set(c, raw);
        assertThrows(SQLFeatureNotSupportedException.class, () -> c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
        assertThrows(SQLFeatureNotSupportedException.class, () -> c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT));
        assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareStatement("SELECT secret FROM users", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
        assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareStatement("SELECT secret FROM users", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT));
        verifyNoInteractions(raw);
    }

    @Test void allConnectionPrepareOverloadsValidateBeforeNativePrepare() throws Exception {
        DadpProxyConnection c = connection();
        Connection raw = mock(Connection.class);
        com.dadp.jdbc.config.ProxyConfig config = mock(com.dadp.jdbc.config.ProxyConfig.class);
        when(config.isStartupReady()).thenReturn(true);
        for (String field : new String[]{"actualConnection", "config", "wrapperRuntimeAvailable"}) {
            java.lang.reflect.Field f = DadpProxyConnection.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(c, field.equals("actualConnection") ? raw : field.equals("config") ? config : true);
        }
        String sql = "INSERT INTO users(secret) VALUES ('plain')";
        doCallRealMethod().when(c).prepareStatement(sql);
        doCallRealMethod().when(c).prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        doCallRealMethod().when(c).prepareStatement(sql, new int[]{1});
        doCallRealMethod().when(c).prepareStatement(sql, new String[]{"id"});
        doCallRealMethod().when(c).prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        doCallRealMethod().when(c).prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT);
        assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareStatement(sql));
        assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS));
        assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareStatement(sql, new int[]{1}));
        assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareStatement(sql, new String[]{"id"}));
        assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT));
        String call = "INSERT INTO users(secret) VALUES (?)";
        doCallRealMethod().when(c).prepareCall(call);
        doCallRealMethod().when(c).prepareCall(call, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        doCallRealMethod().when(c).prepareCall(call, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT);
        for (int pass = 0; pass < 2; pass++) {
            assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareCall(call));
            assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareCall(call, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
            assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareCall(call, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT));
            when(c.getPolicyResolver().getAllMappings()).thenReturn(Collections.emptyMap());
        }
        verifyNoInteractions(raw);
    }

    private DadpProxyConnection connection() {
        DadpProxyConnection c = mock(DadpProxyConnection.class);
        PolicyResolver p = mock(PolicyResolver.class);
        when(c.getPolicyResolver()).thenReturn(p);
        when(p.getAllMappings()).thenReturn(Collections.singletonMap("public.users.secret", "ABCD2345"));
        return c;
    }

    @Test void rejectAmbiguousAndNonBindWrites() {
        DadpProxyConnection c = connection();
        String[] rejected = {
            "INSERT INTO users(secret) VALUES ('plain')", "INSERT INTO users(secret) VALUES (123)",
            "INSERT INTO users(secret) VALUES (upper(?))", "INSERT INTO users(secret) SELECT ?",
            "INSERT INTO users(secret) VALUES (?),(?)", "INSERT INTO users VALUES (?)",
            "INSERT INTO users(secret) VALUES (?) ON DUPLICATE KEY UPDATE secret='plain'",
            "INSERT INTO users(secret) VALUES (?) ON CONFLICT DO UPDATE SET secret='plain'",
            "UPDATE users SET secret='plain'", "UPDATE users SET secret=concat(?,?)",
            "/* app */ INSERT INTO users(secret) VALUES (?)", "nonsense", "CALL plaintext_writer(?)",
            "SELECT secret INTO copy FROM users", "INSERT INTO users(secret) VALUES (?); SELECT 1"
        };
        for (String sql : rejected) assertThrows(SQLFeatureNotSupportedException.class,
                () -> EnginePreparedSqlGuard.validate(sql, c), sql);
    }

    @Test void supportedPositionalPlans() throws Exception {
        DadpProxyConnection c = connection();
        assertEquals(Collections.singleton(2), EnginePreparedSqlGuard.validate("INSERT INTO public.users(id, secret) VALUES (?, ?)", c));
        assertEquals(Collections.singleton(1), EnginePreparedSqlGuard.validate("UPDATE users SET secret=? WHERE id=?", c));
        assertTrue(EnginePreparedSqlGuard.validate("SELECT secret FROM users WHERE id=?", c).isEmpty());
    }

    @Test void protectedNonTextSettersNeverReachDelegate() throws Exception {
        PreparedStatement raw = mock(PreparedStatement.class);
        DadpProxyPreparedStatement p = new DadpProxyPreparedStatement(raw, "INSERT INTO users(secret) VALUES (?)", connection());
        assertThrows(SQLFeatureNotSupportedException.class, () -> p.setInt(1, 12345));
        assertThrows(SQLFeatureNotSupportedException.class, () -> p.setObject(1, 12345, Types.VARCHAR));
        assertThrows(SQLFeatureNotSupportedException.class, () -> p.setObject(1, 12345));
        assertThrows(SQLFeatureNotSupportedException.class, () -> p.setObject(1, 12345, Types.VARCHAR, 5));
        assertThrows(SQLFeatureNotSupportedException.class, () -> p.setCharacterStream(1, new StringReader("plain")));
        assertThrows(SQLFeatureNotSupportedException.class, () -> p.setBinaryStream(1, new ByteArrayInputStream(new byte[]{1})));
        assertThrows(SQLFeatureNotSupportedException.class, () -> p.setBytes(1, new byte[]{1}));
        assertThrows(SQLFeatureNotSupportedException.class, () -> p.setBlob(1, mock(Blob.class)));
        assertThrows(SQLFeatureNotSupportedException.class, () -> p.setClob(1, mock(Clob.class)));
        verifyNoInteractions(raw);
        p.setNull(1, Types.VARCHAR);
        verify(raw).setNull(1, Types.VARCHAR);
    }

    @Test void directConstructionAndSqlOverloadsCannotBypassGuard() throws Exception {
        PreparedStatement raw = mock(PreparedStatement.class);
        DadpProxyConnection c = connection();
        DadpProxyPreparedStatement p = new DadpProxyPreparedStatement(raw, "/* app */ INSERT INTO users(secret) VALUES (?)", c);
        assertThrows(SQLFeatureNotSupportedException.class, () -> p.setString(1, "plain"));
        assertThrows(SQLFeatureNotSupportedException.class, p::executeUpdate);
        assertThrows(SQLFeatureNotSupportedException.class, p::execute);
        assertThrows(SQLFeatureNotSupportedException.class, p::addBatch);
        assertThrows(SQLFeatureNotSupportedException.class, () -> p.execute("INSERT INTO users(secret) VALUES ('plain')"));
        verifyNoInteractions(raw);
    }

    @Test void policyArrivalInvalidatesPreviouslyPlaintextBoundStatementAndBatch() throws Exception {
        PreparedStatement raw = mock(PreparedStatement.class);
        DadpProxyConnection c = connection();
        PolicyResolver policies = c.getPolicyResolver();
        when(policies.getAllMappings()).thenReturn(Collections.emptyMap());
        DadpProxyPreparedStatement p = new DadpProxyPreparedStatement(raw, "INSERT INTO users(secret) VALUES (?)", c);
        p.setString(1, "plain-before-sync");
        p.addBatch();
        when(policies.getAllMappings()).thenReturn(Collections.singletonMap("public.users.secret", "ABCD2345"));
        assertEquals("HY010", assertThrows(SQLException.class, p::executeUpdate).getSQLState());
        assertEquals("HY010", assertThrows(SQLException.class, p::executeBatch).getSQLState());
        assertThrows(SQLException.class, () -> p.setString(1, "new-value"));
        verify(raw, never()).executeUpdate(); verify(raw, never()).executeBatch();
    }

    @Test void clearedOrFailedRebindCannotReusePreviouslyAcceptedNull() throws Exception {
        PreparedStatement raw = mock(PreparedStatement.class);
        DadpProxyPreparedStatement p = new DadpProxyPreparedStatement(raw, "INSERT INTO users(secret) VALUES (?)", connection());
        p.setNull(1, Types.VARCHAR);
        p.clearParameters();
        assertThrows(SQLFeatureNotSupportedException.class, p::executeUpdate);
        assertThrows(SQLFeatureNotSupportedException.class, p::addBatch);
        p.setNull(1, Types.VARCHAR);
        assertThrows(SQLFeatureNotSupportedException.class, () -> p.setInt(1, 12345));
        assertThrows(SQLFeatureNotSupportedException.class, p::executeUpdate);
        verify(raw, never()).executeUpdate();
        p.setNull(1, Types.VARCHAR);
        p.executeUpdate();
        verify(raw).executeUpdate();
    }

    @Test void invalidNativeIndexRemainsNativeError() throws Exception {
        PreparedStatement raw = mock(PreparedStatement.class);
        doThrow(new SQLException("bad index", "07009")).when(raw).setInt(0, 123);
        DadpProxyPreparedStatement p = new DadpProxyPreparedStatement(raw, "INSERT INTO users(secret) VALUES (?)", connection());
        assertEquals("07009", assertThrows(SQLException.class, () -> p.setInt(0, 123)).getSQLState());
    }

    @Test void failedNativeRebindInvalidatesPreviousBindIncludingLargeUpdate() throws Exception {
        PreparedStatement raw = mock(PreparedStatement.class);
        DadpProxyPreparedStatement p = new DadpProxyPreparedStatement(raw, "INSERT INTO users(secret) VALUES (?)", connection());
        p.setNull(1, Types.VARCHAR);
        doThrow(new SQLException("native rebind rejected", "22000")).when(raw).setNull(1, Types.VARCHAR);
        assertThrows(SQLException.class, () -> p.setNull(1, Types.VARCHAR));
        assertThrows(SQLFeatureNotSupportedException.class, p::executeLargeUpdate);
        assertThrows(SQLFeatureNotSupportedException.class, p::executeQuery);
        verify(raw, never()).executeUpdate(); verify(raw, never()).executeQuery();
    }
}
