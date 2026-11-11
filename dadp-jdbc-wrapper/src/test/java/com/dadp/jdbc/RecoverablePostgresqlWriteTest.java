package com.dadp.jdbc;

import com.dadp.hub.crypto.exception.TransientCryptoException;
import java.sql.*;
import java.util.*;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoverablePostgresqlWriteTest {
    @Test void retriesOnlyExplicitTemporaryFailures() {
        for (String s:new String[]{"08001","08006","08007","57P01","25006","40001","40P01","DPT01"})
            assertTrue(RecoverablePostgresqlWrite.retryable(new SQLException("synthetic",s)),s);
        assertTrue(RecoverablePostgresqlWrite.retryable(
                new RuntimeException(new SQLException("wrapped",new TransientCryptoException("temporary")))));
        assertFalse(RecoverablePostgresqlWrite.retryable(new SQLException("unknown external error","38000")));
    }
    @Test void securityValidationAndCancellationOverrideTransientCauses() {
        for (String s:new String[]{"28000","28P01","23505","22023","42501","57014","DPT02"})
            assertFalse(RecoverablePostgresqlWrite.retryable(new SQLException("terminal",s,
                    new TransientCryptoException("nested"))),s);
        assertFalse(RecoverablePostgresqlWrite.retryable(
                new SQLException("transport","08001",new SSLHandshakeException("invalid trust"))));
        assertFalse(RecoverablePostgresqlWrite.retryable(
                new SQLNonTransientConnectionException("invalid TLS","08001")));
    }
    @Test void refusesUnsupportedWritesBeforeOpeningAnyConnection() throws Exception {
        RecoverablePostgresqlWrite r=new RecoverablePostgresqlWrite(
                "jdbc:dadp:postgresql://127.0.0.1:1/absent",new Properties(),"test",1000);
        for (String sql:new String[]{"SELECT 1","INSERT INTO users(name) VALUES('inline')",
                "UPDATE users SET name=?; DELETE FROM users","/*x*/ DELETE FROM users"}) {
            SQLException e=assertThrows(SQLException.class,()->r.execute(UUID.randomUUID(),sql,null,"x"));
            assertEquals("0A000",e.getSQLState());
        }
        SQLException e=assertThrows(SQLException.class,()->r.execute(UUID.randomUUID(),
                "INSERT INTO users(name) VALUES(?)","id",new Object()));
        assertEquals("0A000",e.getSQLState());
    }
    @Test void rejectsInvalidRecoveryConfiguration() {
        assertThrows(IllegalArgumentException.class,()->new RecoverablePostgresqlWrite(
                "jdbc:postgresql://host/db",new Properties(),"test",1000));
        assertThrows(IllegalArgumentException.class,()->new RecoverablePostgresqlWrite(
                "jdbc:dadp:postgresql://host/db",new Properties(),"../other",1000));
    }
}
