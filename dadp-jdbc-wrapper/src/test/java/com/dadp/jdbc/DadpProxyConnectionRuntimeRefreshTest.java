package com.dadp.jdbc;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.StoragePathResolver;
import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.jdbc.stats.TelemetryStatsSender;
import com.dadp.jdbc.sync.JdbcBootstrapOrchestrator;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DadpProxyConnectionRuntimeRefreshTest {

    @BeforeEach
    void disableStartupWait() throws Exception {
        System.setProperty("dadp.wrapper.runtime.startup-wait-ms", "0");
        deleteRuntimeRoot();
    }

    @AfterEach
    void cleanup() throws Exception {
        System.clearProperty("dadp.wrapper.runtime.startup-wait-ms");
        deleteRuntimeRoot();
    }

    @Test
    void reusesOrchestratorAdapterButReappliesRuntimeCryptoMode() throws Exception {
        DadpProxyConnection connection = new DadpProxyConnection(
                mock(Connection.class),
                "jdbc:postgresql://localhost/test",
                Collections.emptyMap());
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

    @Test
    void closeShutsDownTelemetryStatsSender() throws Exception {
        Connection actualConnection = mock(Connection.class);
        DadpProxyConnection connection = new DadpProxyConnection(
                actualConnection,
                "jdbc:postgresql://localhost/test",
                Collections.emptyMap());

        EndpointStorage storage = mock(EndpointStorage.class);
        when(storage.loadEndpoints()).thenReturn(new EndpointStorage.EndpointData());
        TelemetryStatsSender sender = spy(new TelemetryStatsSender(storage, "wtenant_test", "alias_test"));
        setField(connection, "telemetryStatsSender", sender);

        connection.close();

        verify(sender, times(1)).close();
        verify(actualConnection, times(1)).close();
    }

    @Test
    void startupWithoutRuntimeFailsClosedBeforePreparedStatementReachesDriver() throws Exception {
        Connection actualConnection = mock(Connection.class);
        DadpProxyConnection connection = new DadpProxyConnection(
                actualConnection,
                "jdbc:postgresql://localhost/test",
                Collections.emptyMap());

        assertThrows(SQLException.class,
                () -> connection.prepareStatement("insert into users(email) values (?)"));
        verify(actualConnection, never()).prepareStatement("insert into users(email) values (?)");
    }

    @Test
    void startupWithRuntimeDisabledUsesExplicitPassthrough() throws Exception {
        writeRuntimeProxyConfig("disabled-alias", "wtenant_disabled", false, false, "remote");
        Connection actualConnection = mock(Connection.class);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        when(actualConnection.prepareStatement("select 1")).thenReturn(preparedStatement);

        DadpProxyConnection connection = new DadpProxyConnection(
                actualConnection,
                "jdbc:postgresql://localhost/test",
                Collections.emptyMap());

        connection.prepareStatement("select 1");

        verify(actualConnection, times(1)).prepareStatement("select 1");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = DadpProxyConnection.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void deleteRuntimeRoot() throws IOException {
        Path root = Paths.get(StoragePathResolver.resolveWrapperStorageRoot());
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static void writeRuntimeProxyConfig(String alias,
                                                String tenantId,
                                                boolean enabled,
                                                boolean failOpen,
                                                String cryptoMode) throws IOException {
        Path storageDir = Paths.get(StoragePathResolver.resolveStorageDir(alias));
        Files.createDirectories(storageDir);
        String json = "{\n"
                + "  \"tenantId\": \"" + tenantId + "\",\n"
                + "  \"alias\": \"" + alias + "\",\n"
                + "  \"runtimeVersion\": \"1\",\n"
                + "  \"runtime\": {\n"
                + "    \"hubUrl\": \"http://dadp-hub:9004\",\n"
                + "    \"enabled\": " + enabled + ",\n"
                + "    \"failOpen\": " + failOpen + ",\n"
                + "    \"cryptoMode\": \"" + cryptoMode + "\"\n"
                + "  }\n"
                + "}\n";
        Files.write(storageDir.resolve("proxy-config.json"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
