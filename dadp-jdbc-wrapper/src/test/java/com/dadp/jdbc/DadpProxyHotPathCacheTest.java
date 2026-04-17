package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.common.sync.policy.PolicyResolver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DadpProxyHotPathCacheTest {

    @Test
    void resultSetParsedPathCachesDecryptPlanAcrossRepeatedColumnAccess() throws Exception {
        ResultSet actualResultSet = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        DadpProxyConnection proxyConnection = mock(DadpProxyConnection.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter adapter = mock(DirectCryptoAdapter.class);

        when(actualResultSet.getString(1)).thenReturn("enc-index-1");
        when(actualResultSet.getString("email3_0_")).thenReturn("enc-label-1");
        when(actualResultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnName(1)).thenReturn("email");
        when(metaData.getColumnLabel(1)).thenReturn("email3_0_");

        when(proxyConnection.getDatasourceId()).thenReturn("ds_test");
        when(proxyConnection.getCurrentSchemaName()).thenReturn(null);
        when(proxyConnection.getCurrentDatabaseName()).thenReturn("testdb");
        when(proxyConnection.getPolicyResolver()).thenReturn(policyResolver);
        when(proxyConnection.getDirectCryptoAdapter()).thenReturn(adapter);
        when(proxyConnection.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenReturn(7L);
        when(policyResolver.resolvePolicy("ds_test", "testdb", "users", "email")).thenReturn("policy-email");
        when(adapter.decrypt("enc-index-1", "policy-email")).thenReturn("plain-index-1");
        when(adapter.decrypt("enc-label-1", "policy-email")).thenReturn("plain-label-1");

        DadpProxyResultSet proxyResultSet = new DadpProxyResultSet(
                actualResultSet,
                "SELECT user0_.email AS email3_0_ FROM users user0_ WHERE user0_.id = ?",
                proxyConnection);

        assertEquals("plain-index-1", proxyResultSet.getString(1));
        assertEquals("plain-label-1", proxyResultSet.getString("email3_0_"));

        verify(policyResolver, times(1)).resolvePolicy("ds_test", "testdb", "users", "email");
        verify(metaData, times(1)).getColumnName(1);
        verify(metaData, times(1)).getColumnLabel(1);
    }

    @Test
    void preparedStatementCachesInsertEncryptionPlanAcrossRepeatedBinds() throws Exception {
        PreparedStatement actualPreparedStatement = mock(PreparedStatement.class);
        DadpProxyConnection proxyConnection = mock(DadpProxyConnection.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter adapter = mock(DirectCryptoAdapter.class);

        when(proxyConnection.getDatasourceId()).thenReturn("ds_test");
        when(proxyConnection.getCurrentSchemaName()).thenReturn(null);
        when(proxyConnection.getCurrentDatabaseName()).thenReturn("testdb");
        when(proxyConnection.getPolicyResolver()).thenReturn(policyResolver);
        when(proxyConnection.getDirectCryptoAdapter()).thenReturn(adapter);
        when(proxyConnection.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenReturn(11L);
        when(policyResolver.resolvePolicy("ds_test", "testdb", "users", "email")).thenReturn("policy-email");
        when(adapter.encrypt("alice@example.com", "policy-email")).thenReturn("enc-alice");
        when(adapter.encrypt("bob@example.com", "policy-email")).thenReturn("enc-bob");

        DadpProxyPreparedStatement proxyPreparedStatement = new DadpProxyPreparedStatement(
                actualPreparedStatement,
                "INSERT INTO users (email) VALUES (?)",
                proxyConnection);

        proxyPreparedStatement.setString(1, "alice@example.com");
        proxyPreparedStatement.setString(1, "bob@example.com");

        verify(policyResolver, times(1)).resolvePolicy("ds_test", "testdb", "users", "email");
        verify(actualPreparedStatement).setString(1, "enc-alice");
        verify(actualPreparedStatement).setString(1, "enc-bob");
    }

    @Test
    void preparedStatementCachesSearchEncryptionPlanAcrossRepeatedBinds() throws Exception {
        PreparedStatement actualPreparedStatement = mock(PreparedStatement.class);
        DadpProxyConnection proxyConnection = mock(DadpProxyConnection.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter adapter = mock(DirectCryptoAdapter.class);

        when(proxyConnection.getDatasourceId()).thenReturn("ds_test");
        when(proxyConnection.getCurrentSchemaName()).thenReturn(null);
        when(proxyConnection.getCurrentDatabaseName()).thenReturn("testdb");
        when(proxyConnection.getPolicyResolver()).thenReturn(policyResolver);
        when(proxyConnection.getDirectCryptoAdapter()).thenReturn(adapter);
        when(proxyConnection.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenReturn(13L);
        when(policyResolver.resolvePolicy("ds_test", "testdb", "users", "email")).thenReturn("policy-email");
        when(policyResolver.isSearchEncryptionNeeded("policy-email")).thenReturn(true);
        when(adapter.isEndpointAvailable()).thenReturn(true);
        when(adapter.encryptForSearch("alice@example.com", "policy-email")).thenReturn("search-alice");
        when(adapter.encryptForSearch("bob@example.com", "policy-email")).thenReturn("search-bob");

        DadpProxyPreparedStatement proxyPreparedStatement = new DadpProxyPreparedStatement(
                actualPreparedStatement,
                "SELECT * FROM users WHERE email = ?",
                proxyConnection);

        proxyPreparedStatement.setString(1, "alice@example.com");
        proxyPreparedStatement.setString(1, "bob@example.com");

        verify(policyResolver, times(1)).resolvePolicy("ds_test", "testdb", "users", "email");
        verify(policyResolver, times(1)).isSearchEncryptionNeeded("policy-email");
        verify(actualPreparedStatement).setString(1, "search-alice");
        verify(actualPreparedStatement).setString(1, "search-bob");
    }
}
