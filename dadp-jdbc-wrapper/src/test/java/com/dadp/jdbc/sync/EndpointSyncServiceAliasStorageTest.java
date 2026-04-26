package com.dadp.jdbc.sync;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EndpointSyncServiceAliasStorageTest {

    @Test
    void commonEndpointSyncServiceUsesAliasScopedStorageByDefault() {
        com.dadp.common.sync.endpoint.EndpointSyncService service =
                new com.dadp.common.sync.endpoint.EndpointSyncService(
                        "http://hub:9004", "pi_test", "alias-storage-test");

        assertTrue(service.getStoragePath().replace('\\', '/').contains("/alias-storage-test/crypto-endpoints.json"));
    }

    @Test
    void commonEndpointSyncServiceRejectsMissingAlias() {
        assertThrows(IllegalArgumentException.class,
                () -> new com.dadp.common.sync.endpoint.EndpointSyncService("http://hub:9004", "pi_test", null));
    }

    @Test
    void deprecatedWrapperEndpointSyncServiceUsesAliasScopedStorageByDefault() {
        com.dadp.jdbc.endpoint.EndpointSyncService service =
                new com.dadp.jdbc.endpoint.EndpointSyncService(
                        "http://hub:9004", "pi_test", "alias-storage-test");

        assertTrue(service.getStoragePath().replace('\\', '/').contains("/alias-storage-test/crypto-endpoints.json"));
    }

    @Test
    void deprecatedWrapperEndpointSyncServiceRejectsMissingAlias() {
        assertThrows(IllegalArgumentException.class,
                () -> new com.dadp.jdbc.endpoint.EndpointSyncService("http://hub:9004", "pi_test", null));
    }
}
