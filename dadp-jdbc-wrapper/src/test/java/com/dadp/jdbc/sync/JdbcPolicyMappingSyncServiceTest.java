package com.dadp.jdbc.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.HubIdManager;
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
                schemaStorage,
                "ds-test");

        Field hubIdManagerField = JdbcPolicyMappingSyncService.class.getDeclaredField("hubIdManager");
        hubIdManagerField.setAccessible(true);
        HubIdManager hubIdManager = (HubIdManager) hubIdManagerField.get(service);
        hubIdManager.setHubId("hub-test", false);

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
        assertEquals("hub-test", endpointData.getHubId());
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
                schemaStorage,
                "ds-test");

        Field instanceIdField = JdbcPolicyMappingSyncService.class.getDeclaredField("instanceId");
        instanceIdField.setAccessible(true);
        assertEquals("alias-only-wrapper", instanceIdField.get(service));

        Field hubIdManagerField = JdbcPolicyMappingSyncService.class.getDeclaredField("hubIdManager");
        hubIdManagerField.setAccessible(true);
        HubIdManager hubIdManager = (HubIdManager) hubIdManagerField.get(service);
        hubIdManager.setHubId("pi_alias_only", true);

        assertTrue(configStorage.getStoragePath().replace('\\', '/').contains("/proxy-config.json"));
        assertNotNull(configStorage.loadConfig("http://hub:9004", "alias-only-wrapper"));
    }
}
