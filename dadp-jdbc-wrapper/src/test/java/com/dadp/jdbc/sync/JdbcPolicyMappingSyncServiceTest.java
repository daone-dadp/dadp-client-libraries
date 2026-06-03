package com.dadp.jdbc.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
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
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcPolicyMappingSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesStatsAggregatorValuesFromPolicySnapshotEndpoint() throws Exception {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "crypto-endpoints.json");
        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");

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
        tenantIdManager.setTenantId("hub-test", false);

        MappingSyncService.StatsAggregatorInfo statsAggregator = new MappingSyncService.StatsAggregatorInfo();
        statsAggregator.setEnabled(Boolean.TRUE);
        statsAggregator.setUrl("http://aggregator:9005");
        statsAggregator.setMode("DIRECT");
        statsAggregator.setSlowThresholdMs(654);

        MappingSyncService.EndpointInfo endpointInfo = new MappingSyncService.EndpointInfo();
        endpointInfo.setCryptoUrl("http://engine:9003");
        endpointInfo.setStatsAggregator(statsAggregator);

        Method method = JdbcPolicyMappingSyncService.class.getDeclaredMethod(
                "saveEndpointFromPolicyMapping", MappingSyncService.EndpointInfo.class);
        method.setAccessible(true);
        method.invoke(service, endpointInfo);

        EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
        assertNotNull(endpointData);
        assertEquals("http://engine:9003", endpointData.getCryptoUrl());
        assertEquals("hub-test", endpointData.getTenantId());
        assertEquals(Long.valueOf(77L), endpointData.getVersion());
        assertEquals(Boolean.TRUE, endpointData.getStatsAggregatorEnabled());
        assertEquals("http://aggregator:9005", endpointData.getStatsAggregatorUrl());
        assertEquals("DIRECT", endpointData.getStatsAggregatorMode());
        assertEquals(Integer.valueOf(654), endpointData.getSlowThresholdMs());
        verify(directCryptoAdapter).setEndpointData(any(EndpointStorage.EndpointData.class));
    }

    @Test
    void usesAliasAsInstanceIdentity() throws Exception {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "crypto-endpoints.json");
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
    void automaticPolicyMappingSyncDoesNotStartUnlessExplicitlyEnabled() throws Exception {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "crypto-endpoints.json");
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

        Field schedulerField = JdbcPolicyMappingSyncService.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        assertNull(schedulerField.get(service));
        assertFalse(service.isEnabled());
        verify(mappingSyncService, never()).checkMappingChange(any(), any());
        verify(mappingSyncService, never()).syncPolicyMappingsAndUpdateVersion(any());
    }

    @Test
    void manualRefreshAppliesEndpointAndCryptoModeFromSnapshot() throws Exception {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "crypto-endpoints.json");
        InstanceConfigStorage configStorage = new InstanceConfigStorage(tempDir.toString(), "proxy-config.json");

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
}
