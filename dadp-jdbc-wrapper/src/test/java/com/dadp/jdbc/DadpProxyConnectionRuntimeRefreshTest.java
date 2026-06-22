package com.dadp.jdbc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.jdbc.sync.JdbcBootstrapOrchestrator;
import java.lang.reflect.Field;
import java.sql.Connection;
import org.junit.jupiter.api.Test;

class DadpProxyConnectionRuntimeRefreshTest {

    @Test
    void reusesOrchestratorAdapterButReappliesRuntimeCryptoMode() throws Exception {
        DadpProxyConnection connection = new DadpProxyConnection(
                mock(Connection.class),
                "jdbc:postgresql://localhost/test");
        DirectCryptoAdapter adapter = spy(new DirectCryptoAdapter(false));
        JdbcBootstrapOrchestrator orchestrator = mock(JdbcBootstrapOrchestrator.class);
        ProxyConfig config = mock(ProxyConfig.class);

        when(orchestrator.getDirectCryptoAdapter()).thenReturn(adapter);
        when(orchestrator.getCachedTenantId()).thenReturn("wtenant_test");
        when(orchestrator.isRuntimeFailOpen()).thenReturn(false);
        when(orchestrator.getRuntimeCryptoMode()).thenReturn("remote", "local");
        when(orchestrator.getRuntimeHubUrl()).thenReturn("http://dadp-hub:9004");

        when(config.getTenantId()).thenReturn("wtenant_fallback");
        when(config.getStorageDir()).thenReturn("/tmp/dadp/wrapper/pg-daone-wrapper");
        when(config.isCryptoLocalFallbackRemote()).thenReturn(false);
        when(config.getCryptoLocalTimeoutMs()).thenReturn(30000);
        when(config.isWrapperCryptoStatsEnabled()).thenReturn(false);
        when(config.getWrapperCryptoStatsAggregationLevel()).thenReturn("1hour");
        when(config.getSingleTransportMode()).thenReturn("json");
        when(config.getEngineTransport()).thenReturn("http");
        when(config.getEngineBinaryPort()).thenReturn(9104);

        setField(connection, "config", config);
        setField(connection, "orchestrator", orchestrator);
        setField(connection, "directCryptoAdapter", adapter);

        connection.getDirectCryptoAdapter();
        connection.getDirectCryptoAdapter();

        verify(adapter, times(1)).setCryptoMode(
                eq("remote"),
                eq("http://dadp-hub:9004"),
                eq(false),
                eq(30000),
                eq("wtenant_test"),
                eq(false),
                eq("1hour"));
        verify(adapter, times(1)).setCryptoMode(
                eq("local"),
                eq("http://dadp-hub:9004"),
                eq(false),
                eq(30000),
                eq("wtenant_test"),
                eq(false),
                eq("1hour"));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = DadpProxyConnection.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
