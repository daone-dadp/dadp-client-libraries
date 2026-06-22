package com.dadp.jdbc.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.WrapperRuntimeConfigManager;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.common.sync.endpoint.EndpointSyncService;
import com.dadp.common.sync.mapping.MappingSyncService;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.sync.schema.SchemaStorage;
import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.jdbc.schema.JdbcSchemaSyncService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcPolicyMappingSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesEngineEndpointInProxyConfigFromPolicySnapshotEndpoint() throws Exception {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "proxy-config.json");
        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");
        assertTrue(configStorage.saveEnrollment(
                "hub-test",
                "wrapper-test",
                "77",
                "http://hub:9004",
                null,
                null,
                null));

        MappingSyncService mappingSyncService = mock(MappingSyncService.class);
        EndpointSyncService endpointSyncService = mock(EndpointSyncService.class);
        JdbcSchemaSyncService jdbcSchemaSyncService = mock(JdbcSchemaSyncService.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter directCryptoAdapter = mock(DirectCryptoAdapter.class);
        ProxyConfig proxyConfig = mock(ProxyConfig.class);
        SchemaStorage schemaStorage = mock(SchemaStorage.class);

        when(proxyConfig.getAlias()).thenReturn("wrapper-test");
        when(proxyConfig.getInstanceId()).thenReturn("wrapper-test");
        when(proxyConfig.getHubUrl()).thenReturn("http://hub:9004");
        when(policyResolver.getCurrentVersion()).thenReturn(77L);

        JdbcPolicyMappingSyncService service = new JdbcPolicyMappingSyncService(
                mappingSyncService,
                endpointSyncService,
                jdbcSchemaSyncService,
                policyResolver,
                directCryptoAdapter,
                endpointStorage,
                proxyConfig,
                configStorage,
                schemaStorage);

        Field tenantIdManagerField = JdbcPolicyMappingSyncService.class.getDeclaredField("tenantIdManager");
        tenantIdManagerField.setAccessible(true);
        WrapperRuntimeConfigManager tenantIdManager = (WrapperRuntimeConfigManager) tenantIdManagerField.get(service);
        tenantIdManager.setWrapperEnrollment("hub-test", "77", false);

        MappingSyncService.EndpointInfo endpointInfo = new MappingSyncService.EndpointInfo();
        endpointInfo.setCryptoUrl("http://engine:9003");

        Method method = JdbcPolicyMappingSyncService.class.getDeclaredMethod(
                "saveEndpointFromPolicyMapping", MappingSyncService.EndpointInfo.class);
        method.setAccessible(true);
        method.invoke(service, endpointInfo);

        EndpointStorage.EndpointData endpointData = configStorage.loadEndpointData();
        assertNotNull(endpointData);
        assertEquals("http://engine:9003", endpointData.getCryptoUrl());
        assertEquals("hub-test", endpointData.getTenantId());
        assertEquals(Long.valueOf(77L), endpointData.getVersion());
        assertNull(endpointData.getStatsAggregatorEnabled());
        assertNull(endpointData.getStatsAggregatorUrl());
        assertNull(endpointData.getStatsAggregatorMode());
        assertNull(endpointData.getSlowThresholdMs());
        verify(directCryptoAdapter).setEndpointData(any(EndpointStorage.EndpointData.class));
    }

    @Test
    void usesAliasAsInstanceIdentity() throws Exception {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "proxy-config.json");
        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");

        MappingSyncService mappingSyncService = mock(MappingSyncService.class);
        EndpointSyncService endpointSyncService = mock(EndpointSyncService.class);
        JdbcSchemaSyncService jdbcSchemaSyncService = mock(JdbcSchemaSyncService.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter directCryptoAdapter = mock(DirectCryptoAdapter.class);
        ProxyConfig proxyConfig = mock(ProxyConfig.class);
        SchemaStorage schemaStorage = mock(SchemaStorage.class);

        when(proxyConfig.getAlias()).thenReturn("alias-only-wrapper");
        when(proxyConfig.getHubUrl()).thenReturn("http://hub:9004");

        JdbcPolicyMappingSyncService service = new JdbcPolicyMappingSyncService(
                mappingSyncService,
                endpointSyncService,
                jdbcSchemaSyncService,
                policyResolver,
                directCryptoAdapter,
                endpointStorage,
                proxyConfig,
                configStorage,
                schemaStorage);

        Field instanceIdField = JdbcPolicyMappingSyncService.class.getDeclaredField("instanceId");
        instanceIdField.setAccessible(true);
        assertEquals("alias-only-wrapper", instanceIdField.get(service));

        Field tenantIdManagerField = JdbcPolicyMappingSyncService.class.getDeclaredField("tenantIdManager");
        tenantIdManagerField.setAccessible(true);
        WrapperRuntimeConfigManager tenantIdManager = (WrapperRuntimeConfigManager) tenantIdManagerField.get(service);
        tenantIdManager.setTenantId("pi_alias_only", true);

        assertTrue(configStorage.getStoragePath().replace('\\', '/').contains("/proxy-config.json"));
        assertNotNull(configStorage.loadConfig("http://hub:9004", "alias-only-wrapper"));
    }

    @Test
    void hubRefreshDoesNotStartAutomaticallyWhenPolicySyncAutoIsDisabled() throws Exception {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "proxy-config.json");
        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");

        MappingSyncService mappingSyncService = mock(MappingSyncService.class);
        EndpointSyncService endpointSyncService = mock(EndpointSyncService.class);
        JdbcSchemaSyncService jdbcSchemaSyncService = mock(JdbcSchemaSyncService.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter directCryptoAdapter = mock(DirectCryptoAdapter.class);
        ProxyConfig proxyConfig = mock(ProxyConfig.class);
        SchemaStorage schemaStorage = mock(SchemaStorage.class);

        when(proxyConfig.getAlias()).thenReturn("manual-sync-wrapper");
        when(proxyConfig.getHubUrl()).thenReturn("http://hub:9004");
        when(proxyConfig.isAutoPolicyMappingSyncEnabled()).thenReturn(false);

        JdbcPolicyMappingSyncService service = new JdbcPolicyMappingSyncService(
                mappingSyncService,
                endpointSyncService,
                jdbcSchemaSyncService,
                policyResolver,
                directCryptoAdapter,
                endpointStorage,
                proxyConfig,
                configStorage,
                schemaStorage);

        service.setInitialized(true, "pi_manual_sync");

        assertTrue(service.isEnabled());
        verify(mappingSyncService, never()).checkMappingChange(any(), any());
        service.shutdown();
    }

    @Test
    void hubRefreshDoesNotStartAutomaticallyWhenPolicySyncAutoIsEnabled() throws Exception {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "proxy-config.json");
        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");

        MappingSyncService mappingSyncService = mock(MappingSyncService.class);
        EndpointSyncService endpointSyncService = mock(EndpointSyncService.class);
        JdbcSchemaSyncService jdbcSchemaSyncService = mock(JdbcSchemaSyncService.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter directCryptoAdapter = mock(DirectCryptoAdapter.class);
        ProxyConfig proxyConfig = mock(ProxyConfig.class);
        SchemaStorage schemaStorage = mock(SchemaStorage.class);

        when(proxyConfig.getAlias()).thenReturn("manual-only-wrapper");
        when(proxyConfig.getHubUrl()).thenReturn("http://hub:9004");
        when(proxyConfig.isAutoPolicyMappingSyncEnabled()).thenReturn(true);

        JdbcPolicyMappingSyncService service = new JdbcPolicyMappingSyncService(
                mappingSyncService,
                endpointSyncService,
                jdbcSchemaSyncService,
                policyResolver,
                directCryptoAdapter,
                endpointStorage,
                proxyConfig,
                configStorage,
                schemaStorage);

        service.setInitialized(true, "pi_manual_only");

        assertTrue(service.isEnabled());
        verify(mappingSyncService, never()).checkMappingChange(any(), any());
        service.shutdown();
    }

    @Test
    void manualRefreshAppliesEndpointAndCryptoModeFromSnapshot() throws Exception {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "proxy-config.json");
        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");
        assertTrue(configStorage.saveEnrollment(
                "wtenant_manual",
                "manual-refresh-wrapper",
                "6",
                "http://hub:9004",
                null,
                null,
                null));

        MappingSyncService mappingSyncService = mock(MappingSyncService.class);
        EndpointSyncService endpointSyncService = mock(EndpointSyncService.class);
        JdbcSchemaSyncService jdbcSchemaSyncService = mock(JdbcSchemaSyncService.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter directCryptoAdapter = mock(DirectCryptoAdapter.class);
        ProxyConfig proxyConfig = mock(ProxyConfig.class);
        SchemaStorage schemaStorage = mock(SchemaStorage.class);

        when(proxyConfig.getAlias()).thenReturn("manual-refresh-wrapper");
        when(proxyConfig.getHubUrl()).thenReturn("http://hub:9004");
        when(proxyConfig.isAutoPolicyMappingSyncEnabled()).thenReturn(false);
        when(proxyConfig.isCryptoLocalFallbackRemote()).thenReturn(true);
        when(proxyConfig.getCryptoLocalTimeoutMs()).thenReturn(30000);
        when(proxyConfig.isWrapperCryptoStatsEnabled()).thenReturn(false);
        when(proxyConfig.getWrapperCryptoStatsAggregationLevel()).thenReturn("1hour");
        when(mappingSyncService.checkMappingChange(any(), any())).thenReturn(true);
        when(mappingSyncService.syncPolicyMappingsAndUpdateVersion(any())).thenReturn(2);

        MappingSyncService.EndpointInfo endpointInfo = new MappingSyncService.EndpointInfo();
        endpointInfo.setCryptoUrl("http://engine:9003");
        MappingSyncService.WrapperConfig wrapperConfig = new MappingSyncService.WrapperConfig();
        wrapperConfig.setEnabled(Boolean.TRUE);
        wrapperConfig.setCryptoMode("local");
        MappingSyncService.PolicySnapshot snapshot = new MappingSyncService.PolicySnapshot();
        snapshot.setVersion(7L);
        snapshot.setEndpoint(endpointInfo);
        snapshot.setWrapperConfig(wrapperConfig);
        when(mappingSyncService.getLastSnapshot()).thenReturn(snapshot);

        JdbcPolicyMappingSyncService service = new JdbcPolicyMappingSyncService(
                mappingSyncService,
                endpointSyncService,
                jdbcSchemaSyncService,
                policyResolver,
                directCryptoAdapter,
                endpointStorage,
                proxyConfig,
                configStorage,
                schemaStorage);

        service.setInitialized(true, "wtenant_manual");
        service.shutdown();
        org.mockito.Mockito.reset(mappingSyncService, directCryptoAdapter);
        when(mappingSyncService.syncPolicyMappingsAndUpdateVersion(any())).thenReturn(2);
        when(mappingSyncService.getLastSnapshot()).thenReturn(snapshot);
        service.refreshNow();

        verify(mappingSyncService).syncPolicyMappingsAndUpdateVersion(any());
        verify(directCryptoAdapter).setEndpointData(any(EndpointStorage.EndpointData.class));
        verify(directCryptoAdapter).setCryptoMode(
                eq("local"),
                eq("http://hub:9004"),
                eq(true),
                eq(30000),
                eq("wtenant_manual"),
                eq(false),
                eq("1hour"));
    }

    @Test
    void manualRefreshDoesNotLoadTenantIdOrCallHubWhenServiceIsNotInitialized() throws Exception {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "proxy-config.json");
        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");

        MappingSyncService mappingSyncService = mock(MappingSyncService.class);
        EndpointSyncService endpointSyncService = mock(EndpointSyncService.class);
        JdbcSchemaSyncService jdbcSchemaSyncService = mock(JdbcSchemaSyncService.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter directCryptoAdapter = mock(DirectCryptoAdapter.class);
        ProxyConfig proxyConfig = mock(ProxyConfig.class);
        SchemaStorage schemaStorage = mock(SchemaStorage.class);

        when(proxyConfig.getAlias()).thenReturn("late-enrolled-wrapper");
        when(proxyConfig.getHubUrl()).thenReturn("http://hub:9004");
        when(proxyConfig.isCryptoLocalFallbackRemote()).thenReturn(true);
        when(proxyConfig.getCryptoLocalTimeoutMs()).thenReturn(30000);
        when(proxyConfig.isWrapperCryptoStatsEnabled()).thenReturn(false);
        when(proxyConfig.getWrapperCryptoStatsAggregationLevel()).thenReturn("1hour");
        when(mappingSyncService.syncPolicyMappingsAndUpdateVersion(any())).thenReturn(1);

        MappingSyncService.EndpointInfo endpointInfo = new MappingSyncService.EndpointInfo();
        endpointInfo.setCryptoUrl("http://engine:9003");
        MappingSyncService.PolicySnapshot snapshot = new MappingSyncService.PolicySnapshot();
        snapshot.setVersion(8L);
        snapshot.setEndpoint(endpointInfo);
        when(mappingSyncService.getLastSnapshot()).thenReturn(snapshot);

        JdbcPolicyMappingSyncService service = new JdbcPolicyMappingSyncService(
                mappingSyncService,
                endpointSyncService,
                jdbcSchemaSyncService,
                policyResolver,
                directCryptoAdapter,
                endpointStorage,
                proxyConfig,
                configStorage,
                schemaStorage);

        service.refreshNow();

        verify(mappingSyncService, never()).syncPolicyMappingsAndUpdateVersion(any());
        verify(directCryptoAdapter, never()).setEndpointData(any(EndpointStorage.EndpointData.class));
    }

    @Test
    void manualRefreshWithoutTenantIdDoesNotCallHub() {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "proxy-config.json");
        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");

        MappingSyncService mappingSyncService = mock(MappingSyncService.class);
        EndpointSyncService endpointSyncService = mock(EndpointSyncService.class);
        JdbcSchemaSyncService jdbcSchemaSyncService = mock(JdbcSchemaSyncService.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter directCryptoAdapter = mock(DirectCryptoAdapter.class);
        ProxyConfig proxyConfig = mock(ProxyConfig.class);
        SchemaStorage schemaStorage = mock(SchemaStorage.class);

        when(proxyConfig.getAlias()).thenReturn("not-enrolled-wrapper");
        when(proxyConfig.getHubUrl()).thenReturn("http://hub:9004");

        JdbcPolicyMappingSyncService service = new JdbcPolicyMappingSyncService(
                mappingSyncService,
                endpointSyncService,
                jdbcSchemaSyncService,
                policyResolver,
                directCryptoAdapter,
                endpointStorage,
                proxyConfig,
                configStorage,
                schemaStorage);

        service.refreshNow();

        verify(mappingSyncService, never()).syncPolicyMappingsAndUpdateVersion(any());
        verify(directCryptoAdapter, never()).setEndpointData(any(EndpointStorage.EndpointData.class));
    }

    @Test
    void manualRefreshAppliesEnabledFalseInMemoryWithoutPersistingIt() throws Exception {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "proxy-config.json");
        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");
        assertTrue(configStorage.saveEnrollment(
                "wtenant_disabled",
                "manual-refresh-disabled-wrapper",
                "10",
                "http://hub:9004",
                null,
                null,
                null));

        MappingSyncService mappingSyncService = mock(MappingSyncService.class);
        EndpointSyncService endpointSyncService = mock(EndpointSyncService.class);
        JdbcSchemaSyncService jdbcSchemaSyncService = mock(JdbcSchemaSyncService.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter directCryptoAdapter = mock(DirectCryptoAdapter.class);
        ProxyConfig proxyConfig = mock(ProxyConfig.class);
        SchemaStorage schemaStorage = mock(SchemaStorage.class);

        when(proxyConfig.getAlias()).thenReturn("manual-refresh-disabled-wrapper");
        when(proxyConfig.getHubUrl()).thenReturn("http://hub:9004");
        when(proxyConfig.isEnabled()).thenReturn(true);
        when(proxyConfig.isCryptoLocalFallbackRemote()).thenReturn(true);
        when(proxyConfig.getCryptoLocalTimeoutMs()).thenReturn(30000);
        when(proxyConfig.getWrapperCryptoStatsAggregationLevel()).thenReturn("1hour");
        when(mappingSyncService.syncPolicyMappingsAndUpdateVersion(any())).thenReturn(1);

        MappingSyncService.WrapperConfig wrapperConfig = new MappingSyncService.WrapperConfig();
        wrapperConfig.setEnabled(Boolean.FALSE);
        wrapperConfig.setCryptoMode("remote");
        wrapperConfig.setFailOpen(Boolean.FALSE);
        MappingSyncService.PolicySnapshot snapshot = new MappingSyncService.PolicySnapshot();
        snapshot.setVersion(11L);
        snapshot.setWrapperConfig(wrapperConfig);
        when(mappingSyncService.getLastSnapshot()).thenReturn(snapshot);

        JdbcPolicyMappingSyncService service = new JdbcPolicyMappingSyncService(
                mappingSyncService,
                endpointSyncService,
                jdbcSchemaSyncService,
                policyResolver,
                directCryptoAdapter,
                endpointStorage,
                proxyConfig,
                configStorage,
                schemaStorage);

        service.setInitialized(true, "wtenant_disabled");
        service.shutdown();
        org.mockito.Mockito.reset(mappingSyncService, proxyConfig);
        when(proxyConfig.isEnabled()).thenReturn(true);
        when(mappingSyncService.syncPolicyMappingsAndUpdateVersion(any())).thenReturn(1);
        when(mappingSyncService.getLastSnapshot()).thenReturn(snapshot);
        service.refreshNow();

        verify(proxyConfig).setEnabled(false);
        Field tenantIdManagerField = JdbcPolicyMappingSyncService.class.getDeclaredField("tenantIdManager");
        tenantIdManagerField.setAccessible(true);
        WrapperRuntimeConfigManager tenantIdManager = (WrapperRuntimeConfigManager) tenantIdManagerField.get(service);
        assertFalse(tenantIdManager.isEnabled());
        String storedJson = new String(Files.readAllBytes(tempDir.resolve("proxy-config.json")), StandardCharsets.UTF_8);
        assertFalse(storedJson.contains("\"enabled\""));
    }
}
