package com.dadp.aop.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dadp.aop.config.DadpAopProperties;
import com.dadp.aop.metadata.EncryptionMetadataInitializer;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.HubIdManager;
import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.common.sync.endpoint.EndpointSyncService;
import com.dadp.common.sync.mapping.MappingSyncService;
import com.dadp.common.sync.policy.PolicyResolver;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;

class AopPolicyMappingSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesStatsAggregatorValuesFromPolicySnapshotEndpoint() throws Exception {
        EndpointStorage endpointStorage = new EndpointStorage(tempDir.toString(), "crypto-endpoints.json");

        MappingSyncService mappingSyncService = mock(MappingSyncService.class);
        EndpointSyncService endpointSyncService = mock(EndpointSyncService.class);
        AopSchemaSyncServiceV2 aopSchemaSyncService = mock(AopSchemaSyncServiceV2.class);
        PolicyResolver policyResolver = mock(PolicyResolver.class);
        DirectCryptoAdapter directCryptoAdapter = mock(DirectCryptoAdapter.class);
        DadpAopProperties properties = mock(DadpAopProperties.class);
        Environment environment = mock(Environment.class);
        EncryptionMetadataInitializer metadataInitializer = mock(EncryptionMetadataInitializer.class);

        when(properties.getHubBaseUrl()).thenReturn("http://hub:9004");
        when(environment.getProperty("spring.application.name")).thenReturn("aop-test");

        AopPolicyMappingSyncService service = new AopPolicyMappingSyncService(
                mappingSyncService,
                endpointSyncService,
                aopSchemaSyncService,
                policyResolver,
                directCryptoAdapter,
                endpointStorage,
                properties,
                environment,
                metadataInitializer);

        Field hubIdManagerField = AopPolicyMappingSyncService.class.getDeclaredField("hubIdManager");
        hubIdManagerField.setAccessible(true);
        HubIdManager hubIdManager = (HubIdManager) hubIdManagerField.get(service);
        hubIdManager.setHubId("hub-test", false);

        Map<String, Object> statsAggregator = new HashMap<String, Object>();
        statsAggregator.put("enabled", Boolean.TRUE);
        statsAggregator.put("url", "http://aggregator:9005");
        statsAggregator.put("mode", "DIRECT");
        statsAggregator.put("slowThresholdMs", Integer.valueOf(321));

        Map<String, Object> endpointInfo = new HashMap<String, Object>();
        endpointInfo.put("cryptoUrl", "http://engine:9003");
        endpointInfo.put("statsAggregator", statsAggregator);

        Method method = AopPolicyMappingSyncService.class.getDeclaredMethod(
                "syncEndpointsFromPolicySnapshot", Map.class);
        method.setAccessible(true);
        method.invoke(service, endpointInfo);

        EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
        assertNotNull(endpointData);
        assertEquals("http://engine:9003", endpointData.getCryptoUrl());
        assertEquals("hub-test", endpointData.getHubId());
        assertEquals(Boolean.TRUE, endpointData.getStatsAggregatorEnabled());
        assertEquals("http://aggregator:9005", endpointData.getStatsAggregatorUrl());
        assertEquals("DIRECT", endpointData.getStatsAggregatorMode());
        assertEquals(Integer.valueOf(321), endpointData.getSlowThresholdMs());
        verify(directCryptoAdapter).setEndpointData(any(EndpointStorage.EndpointData.class));
    }
}
