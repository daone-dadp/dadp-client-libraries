package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.common.sync.policy.PolicyResolver;
import java.sql.*;
import org.junit.jupiter.api.Test;

class SqreamResultSetMetadataTest {
    @Test
    void decryptsUsingSqlLineageWhenDriverDoesNotSupportSchemaOrTableMetadata() throws Exception {
        ResultSet raw = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        DadpProxyConnection connection = mock(DadpProxyConnection.class);
        PolicyResolver policies = mock(PolicyResolver.class);
        DirectCryptoAdapter crypto = mock(DirectCryptoAdapter.class);
        when(raw.getMetaData()).thenReturn(metadata);
        when(raw.getString(1)).thenReturn("encrypted-value");
        when(raw.getObject(1)).thenReturn("encrypted-value");
        when(metadata.getColumnName(1)).thenReturn("name");
        when(metadata.getColumnLabel(1)).thenReturn("name");
        when(metadata.getSchemaName(1)).thenThrow(new SQLFeatureNotSupportedException("getSchemaName in SQResultSetMetaData"));
        when(metadata.getTableName(1)).thenThrow(new SQLFeatureNotSupportedException("getTableName"));
        when(connection.getPolicyResolver()).thenReturn(policies);
        when(connection.getDirectCryptoAdapter()).thenReturn(crypto);
        when(connection.normalizeIdentifier(anyString())).thenAnswer(i -> i.getArgument(0));
        when(connection.resolveLookupSchemaName("public")).thenReturn("public");
        when(policies.getCurrentVersion()).thenReturn(1L);
        when(policies.resolvePolicy(null, "public", "sqream_users", "name")).thenReturn("DAONE234");
        when(crypto.decrypt("encrypted-value", "DAONE234")).thenReturn("original-name");
        DadpProxyResultSet result = new DadpProxyResultSet(raw,
                "SELECT name FROM public.sqream_users", connection);
        assertEquals("original-name", result.getString(1));
        assertEquals("original-name", result.getObject(1));
        verify(policies).resolvePolicy(null, "public", "sqream_users", "name");
        verify(crypto, times(2)).decrypt("encrypted-value", "DAONE234");
    }

    @Test
    void propagatesOperationalMetadataErrorsInsteadOfTreatingThemAsUnsupported() throws Exception {
        ResultSet raw = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        DadpProxyConnection connection = mock(DadpProxyConnection.class);
        when(raw.getMetaData()).thenReturn(metadata);
        when(raw.getObject(1)).thenReturn("encrypted-value");
        when(metadata.getSchemaName(1)).thenThrow(new SQLException("connection lost", "08006"));
        DadpProxyResultSet result = new DadpProxyResultSet(raw,
                "SELECT name FROM public.sqream_users", connection);
        assertThrows(SQLException.class, () -> result.getObject(1));
    }
}
