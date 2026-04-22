package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.common.sync.policy.PolicyResolver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DadpProxyHotPathCacheTest {

    @AfterEach
    void clearPreparedStatementCaches() {
        DadpProxyPreparedStatement.clearHotPathCachesForTest();
    }

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
        when(proxyConnection.getDbVendor()).thenReturn("mysql");
        when(proxyConnection.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenReturn(7L);
        when(policyResolver.resolvePolicy(null, "testdb", "users", "email")).thenReturn("policy-email");
        when(adapter.decrypt("enc-index-1", "policy-email")).thenReturn("plain-index-1");
        when(adapter.decrypt("enc-label-1", "policy-email")).thenReturn("plain-label-1");

        DadpProxyResultSet proxyResultSet = new DadpProxyResultSet(
                actualResultSet,
                "SELECT user0_.email AS email3_0_ FROM users user0_ WHERE user0_.id = ?",
                proxyConnection);

        assertEquals("plain-index-1", proxyResultSet.getString(1));
        assertEquals("plain-label-1", proxyResultSet.getString("email3_0_"));

        verify(policyResolver, times(1)).resolvePolicy(null, "testdb", "users", "email");
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
        when(proxyConnection.getDbVendor()).thenReturn("mysql");
        when(proxyConnection.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenReturn(11L);
        when(policyResolver.resolvePolicy(null, "testdb", "users", "email")).thenReturn("policy-email");
        when(adapter.encrypt("alice@example.com", "policy-email")).thenReturn("enc-alice");
        when(adapter.encrypt("bob@example.com", "policy-email")).thenReturn("enc-bob");

        DadpProxyPreparedStatement proxyPreparedStatement = new DadpProxyPreparedStatement(
                actualPreparedStatement,
                "INSERT INTO users (email) VALUES (?)",
                proxyConnection);

        proxyPreparedStatement.setString(1, "alice@example.com");
        proxyPreparedStatement.setString(1, "bob@example.com");

        verify(policyResolver, times(1)).getCurrentVersion();
        verify(policyResolver, times(1)).resolvePolicy(null, "testdb", "users", "email");
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
        when(proxyConnection.getDbVendor()).thenReturn("mysql");
        when(proxyConnection.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenReturn(13L);
        when(policyResolver.resolvePolicy(null, "testdb", "users", "email")).thenReturn("policy-email");
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

        verify(policyResolver, times(1)).resolvePolicy(null, "testdb", "users", "email");
        verify(policyResolver, times(1)).isSearchEncryptionNeeded("policy-email");
        verify(actualPreparedStatement).setString(1, "search-alice");
        verify(actualPreparedStatement).setString(1, "search-bob");
    }

    @Test
    void preparedStatementReusesCompiledPlanAcrossStatementInstances() throws Exception {
        PreparedStatement actualPreparedStatement1 = mock(PreparedStatement.class);
        PreparedStatement actualPreparedStatement2 = mock(PreparedStatement.class);
        DadpProxyConnection proxyConnection = mock(DadpProxyConnection.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter adapter = mock(DirectCryptoAdapter.class);

        when(proxyConnection.getDatasourceId()).thenReturn("ds_test");
        when(proxyConnection.getCurrentSchemaName()).thenReturn(null);
        when(proxyConnection.getCurrentDatabaseName()).thenReturn("testdb");
        when(proxyConnection.getPolicyResolver()).thenReturn(policyResolver);
        when(proxyConnection.getDirectCryptoAdapter()).thenReturn(adapter);
        when(proxyConnection.getDbVendor()).thenReturn("mysql");
        when(proxyConnection.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenReturn(21L);
        when(policyResolver.resolvePolicy(null, "testdb", "users", "email")).thenReturn("policy-email");
        when(adapter.encrypt("alice@example.com", "policy-email")).thenReturn("enc-alice");
        when(adapter.encrypt("bob@example.com", "policy-email")).thenReturn("enc-bob");

        DadpProxyPreparedStatement first = new DadpProxyPreparedStatement(
                actualPreparedStatement1,
                "INSERT INTO users (email) VALUES (?)",
                proxyConnection);
        DadpProxyPreparedStatement second = new DadpProxyPreparedStatement(
                actualPreparedStatement2,
                "INSERT INTO users (email) VALUES (?)",
                proxyConnection);

        first.setString(1, "alice@example.com");
        second.setString(1, "bob@example.com");

        verify(policyResolver, times(1)).resolvePolicy(null, "testdb", "users", "email");
        verify(actualPreparedStatement1).setString(1, "enc-alice");
        verify(actualPreparedStatement2).setString(1, "enc-bob");
    }

    @Test
    void preparedStatementBypassesStringProcessingForAllPassthroughStatement() throws Exception {
        PreparedStatement actualPreparedStatement1 = mock(PreparedStatement.class);
        PreparedStatement actualPreparedStatement2 = mock(PreparedStatement.class);
        DadpProxyConnection proxyConnection = mock(DadpProxyConnection.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter adapter = mock(DirectCryptoAdapter.class);

        when(proxyConnection.getDatasourceId()).thenReturn("ds_test");
        when(proxyConnection.getCurrentSchemaName()).thenReturn(null);
        when(proxyConnection.getCurrentDatabaseName()).thenReturn("testdb");
        when(proxyConnection.getPolicyResolver()).thenReturn(policyResolver);
        when(proxyConnection.getDirectCryptoAdapter()).thenReturn(adapter);
        when(proxyConnection.getDbVendor()).thenReturn("mysql");
        when(proxyConnection.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenReturn(22L);
        when(policyResolver.resolvePolicy(null, "testdb", "dadp_udf_bench_text", "run_id")).thenReturn(null);
        when(policyResolver.resolvePolicy(null, "testdb", "dadp_udf_bench_text", "plain_text")).thenReturn(null);

        DadpProxyPreparedStatement first = new DadpProxyPreparedStatement(
                actualPreparedStatement1,
                "INSERT INTO DADP_UDF_BENCH_TEXT (RUN_ID, PLAIN_TEXT) VALUES (?, ?)",
                proxyConnection);
        DadpProxyPreparedStatement second = new DadpProxyPreparedStatement(
                actualPreparedStatement2,
                "INSERT INTO DADP_UDF_BENCH_TEXT (RUN_ID, PLAIN_TEXT) VALUES (?, ?)",
                proxyConnection);

        first.setString(1, "run-1");
        first.setString(2, "plain-1");
        second.setString(1, "run-2");
        second.setString(2, "plain-2");

        verify(policyResolver, times(1)).resolvePolicy(null, "testdb", "dadp_udf_bench_text", "run_id");
        verify(policyResolver, times(1)).resolvePolicy(null, "testdb", "dadp_udf_bench_text", "plain_text");
        verify(adapter, never()).encrypt(anyString(), anyString());
        verify(actualPreparedStatement1).setString(1, "run-1");
        verify(actualPreparedStatement1).setString(2, "plain-1");
        verify(actualPreparedStatement2).setString(1, "run-2");
        verify(actualPreparedStatement2).setString(2, "plain-2");
    }

    @Test
    void preparedStatementUsesProtectedColumnIndexForPrepareTimePlaintextClassification() throws Exception {
        PreparedStatement actualPreparedStatement = mock(PreparedStatement.class);
        DadpProxyConnection proxyConnection = mock(DadpProxyConnection.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter adapter = mock(DirectCryptoAdapter.class);

        when(proxyConnection.getDatasourceId()).thenReturn("ds_test");
        when(proxyConnection.getCurrentSchemaName()).thenReturn(null);
        when(proxyConnection.getCurrentDatabaseName()).thenReturn("testdb");
        when(proxyConnection.getPolicyResolver()).thenReturn(policyResolver);
        when(proxyConnection.getDirectCryptoAdapter()).thenReturn(adapter);
        when(proxyConnection.getDbVendor()).thenReturn("mysql");
        when(proxyConnection.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenReturn(23L);
        when(policyResolver.getProtectedColumnIndex()).thenReturn(
                PolicyResolver.ProtectedColumnIndex.fromMappings(
                        Collections.singletonMap("testdb.users.email", "policy-email"),
                        23L));

        DadpProxyPreparedStatement proxyPreparedStatement = new DadpProxyPreparedStatement(
                actualPreparedStatement,
                "INSERT INTO DADP_UDF_BENCH_TEXT (RUN_ID, PLAIN_TEXT) VALUES (?, ?)",
                proxyConnection);

        proxyPreparedStatement.setString(1, "run-1");
        proxyPreparedStatement.setString(2, "plain-1");

        verify(policyResolver, never()).resolvePolicy(anyString(), anyString(), anyString(), anyString());
        verify(adapter, never()).encrypt(anyString(), anyString());
        verify(actualPreparedStatement).setString(1, "run-1");
        verify(actualPreparedStatement).setString(2, "plain-1");
    }

    @Test
    void preparedStatementUsesProtectedColumnIndexForPrepareTimeEncryptedClassification() throws Exception {
        PreparedStatement actualPreparedStatement = mock(PreparedStatement.class);
        DadpProxyConnection proxyConnection = mock(DadpProxyConnection.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter adapter = mock(DirectCryptoAdapter.class);

        when(proxyConnection.getDatasourceId()).thenReturn("ds_test");
        when(proxyConnection.getCurrentSchemaName()).thenReturn(null);
        when(proxyConnection.getCurrentDatabaseName()).thenReturn("testdb");
        when(proxyConnection.getPolicyResolver()).thenReturn(policyResolver);
        when(proxyConnection.getDirectCryptoAdapter()).thenReturn(adapter);
        when(proxyConnection.getDbVendor()).thenReturn("mysql");
        when(proxyConnection.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenReturn(24L);
        when(policyResolver.getProtectedColumnIndex()).thenReturn(
                PolicyResolver.ProtectedColumnIndex.fromMappings(
                        Collections.singletonMap("testdb.users.email", "policy-email"),
                        24L));
        when(adapter.encrypt("alice@example.com", "policy-email")).thenReturn("enc-alice");

        DadpProxyPreparedStatement proxyPreparedStatement = new DadpProxyPreparedStatement(
                actualPreparedStatement,
                "INSERT INTO users (email) VALUES (?)",
                proxyConnection);

        proxyPreparedStatement.setString(1, "alice@example.com");

        verify(policyResolver, never()).resolvePolicy(anyString(), anyString(), anyString(), anyString());
        verify(actualPreparedStatement).setString(1, "enc-alice");
    }

    @Test
    void preparedStatementReclassifiesWhenProtectedColumnIndexChangesForNewStatements() throws Exception {
        PreparedStatement encryptedPreparedStatement = mock(PreparedStatement.class);
        PreparedStatement passthroughPreparedStatement = mock(PreparedStatement.class);
        DadpProxyConnection proxyConnection = mock(DadpProxyConnection.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter adapter = mock(DirectCryptoAdapter.class);
        AtomicLong policyVersion = new AtomicLong(25L);
        AtomicReference<PolicyResolver.ProtectedColumnIndex> indexRef = new AtomicReference<>(
                PolicyResolver.ProtectedColumnIndex.fromMappings(
                        Collections.singletonMap("testdb.users.email", "policy-email"),
                        25L));

        when(proxyConnection.getDatasourceId()).thenReturn("ds_test");
        when(proxyConnection.getCurrentSchemaName()).thenReturn(null);
        when(proxyConnection.getCurrentDatabaseName()).thenReturn("testdb");
        when(proxyConnection.getPolicyResolver()).thenReturn(policyResolver);
        when(proxyConnection.getDirectCryptoAdapter()).thenReturn(adapter);
        when(proxyConnection.getDbVendor()).thenReturn("mysql");
        when(proxyConnection.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenAnswer(invocation -> policyVersion.get());
        when(policyResolver.getProtectedColumnIndex()).thenAnswer(invocation -> indexRef.get());
        when(adapter.encrypt("alice@example.com", "policy-email")).thenReturn("enc-alice");

        DadpProxyPreparedStatement encryptedStatement = new DadpProxyPreparedStatement(
                encryptedPreparedStatement,
                "INSERT INTO users (email) VALUES (?)",
                proxyConnection);
        encryptedStatement.setString(1, "alice@example.com");

        policyVersion.set(26L);
        indexRef.set(PolicyResolver.ProtectedColumnIndex.fromMappings(Collections.emptyMap(), 26L));

        DadpProxyPreparedStatement passthroughStatement = new DadpProxyPreparedStatement(
                passthroughPreparedStatement,
                "INSERT INTO users (email) VALUES (?)",
                proxyConnection);
        passthroughStatement.setString(1, "bob@example.com");

        verify(policyResolver, never()).resolvePolicy(anyString(), anyString(), anyString(), anyString());
        verify(encryptedPreparedStatement).setString(1, "enc-alice");
        verify(passthroughPreparedStatement).setString(1, "bob@example.com");
    }

    @Test
    void preparedStatementCachesNegativePolicyAcrossSqlShapesUntilPolicyVersionChanges() throws Exception {
        PreparedStatement actualPreparedStatement1 = mock(PreparedStatement.class);
        PreparedStatement actualPreparedStatement2 = mock(PreparedStatement.class);
        PreparedStatement actualPreparedStatement3 = mock(PreparedStatement.class);
        DadpProxyConnection proxyConnection = mock(DadpProxyConnection.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter adapter = mock(DirectCryptoAdapter.class);
        AtomicLong policyVersion = new AtomicLong(30L);

        when(proxyConnection.getDatasourceId()).thenReturn("ds_test");
        when(proxyConnection.getCurrentSchemaName()).thenReturn(null);
        when(proxyConnection.getCurrentDatabaseName()).thenReturn("testdb");
        when(proxyConnection.getPolicyResolver()).thenReturn(policyResolver);
        when(proxyConnection.getDirectCryptoAdapter()).thenReturn(adapter);
        when(proxyConnection.getDbVendor()).thenReturn("mysql");
        when(proxyConnection.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenAnswer(invocation -> policyVersion.get());
        when(policyResolver.resolvePolicy(null, "testdb", "dadp_udf_bench_text", "run_id")).thenReturn(null);

        DadpProxyPreparedStatement insertStatement = new DadpProxyPreparedStatement(
                actualPreparedStatement1,
                "INSERT INTO DADP_UDF_BENCH_TEXT (RUN_ID) VALUES (?)",
                proxyConnection);
        DadpProxyPreparedStatement updateStatement = new DadpProxyPreparedStatement(
                actualPreparedStatement2,
                "UPDATE DADP_UDF_BENCH_TEXT SET RUN_ID = ?",
                proxyConnection);

        insertStatement.setString(1, "run-1");
        updateStatement.setString(1, "run-2");

        verify(policyResolver, times(1)).resolvePolicy(null, "testdb", "dadp_udf_bench_text", "run_id");
        verify(actualPreparedStatement1).setString(1, "run-1");
        verify(actualPreparedStatement2).setString(1, "run-2");

        policyVersion.set(31L);

        DadpProxyPreparedStatement refreshedStatement = new DadpProxyPreparedStatement(
                actualPreparedStatement3,
                "INSERT INTO DADP_UDF_BENCH_TEXT (RUN_ID) VALUES (?)",
                proxyConnection);
        refreshedStatement.setString(1, "run-3");

        verify(policyResolver, times(2)).resolvePolicy(null, "testdb", "dadp_udf_bench_text", "run_id");
        verify(actualPreparedStatement3).setString(1, "run-3");
    }

    @Test
    void preparedStatementCompiledPlanCacheIgnoresDatasourceIdForSameDb() throws Exception {
        PreparedStatement actualPreparedStatement1 = mock(PreparedStatement.class);
        PreparedStatement actualPreparedStatement2 = mock(PreparedStatement.class);
        DadpProxyConnection proxyConnection1 = mock(DadpProxyConnection.class);
        DadpProxyConnection proxyConnection2 = mock(DadpProxyConnection.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter adapter = mock(DirectCryptoAdapter.class);

        when(proxyConnection1.getDatasourceId()).thenReturn("ds_a");
        when(proxyConnection2.getDatasourceId()).thenReturn("ds_b");
        when(proxyConnection1.getCurrentSchemaName()).thenReturn(null);
        when(proxyConnection2.getCurrentSchemaName()).thenReturn(null);
        when(proxyConnection1.getCurrentDatabaseName()).thenReturn("testdb");
        when(proxyConnection2.getCurrentDatabaseName()).thenReturn("testdb");
        when(proxyConnection1.getPolicyResolver()).thenReturn(policyResolver);
        when(proxyConnection2.getPolicyResolver()).thenReturn(policyResolver);
        when(proxyConnection1.getDirectCryptoAdapter()).thenReturn(adapter);
        when(proxyConnection2.getDirectCryptoAdapter()).thenReturn(adapter);
        when(proxyConnection1.getDbVendor()).thenReturn("mysql");
        when(proxyConnection2.getDbVendor()).thenReturn("mysql");
        when(proxyConnection1.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));
        when(proxyConnection2.normalizeIdentifier(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT));

        when(policyResolver.getCurrentVersion()).thenReturn(32L);
        when(policyResolver.resolvePolicy(null, "testdb", "users", "email")).thenReturn("policy-email");
        when(adapter.encrypt("alice@example.com", "policy-email")).thenReturn("enc-alice");
        when(adapter.encrypt("bob@example.com", "policy-email")).thenReturn("enc-bob");

        DadpProxyPreparedStatement first = new DadpProxyPreparedStatement(
                actualPreparedStatement1,
                "INSERT INTO users (email) VALUES (?)",
                proxyConnection1);
        DadpProxyPreparedStatement second = new DadpProxyPreparedStatement(
                actualPreparedStatement2,
                "INSERT INTO users (email) VALUES (?)",
                proxyConnection2);

        first.setString(1, "alice@example.com");
        second.setString(1, "bob@example.com");

        verify(policyResolver, times(1)).resolvePolicy(null, "testdb", "users", "email");
        verify(actualPreparedStatement1).setString(1, "enc-alice");
        verify(actualPreparedStatement2).setString(1, "enc-bob");
    }
}
