package com.dadp.common.sync.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MappingSyncServiceSnapshotTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesStatsAggregatorFromPolicySnapshotEndpoint() throws Exception {
        String json = "{"
                + "\"success\":true,"
                + "\"data\":{"
                + "\"version\":17,"
                + "\"mappings\":[],"
                + "\"endpoint\":{"
                + "\"cryptoUrl\":\"http://engine:9003\","
                + "\"apiBasePath\":\"/api\","
                + "\"statsAggregator\":{"
                + "\"enabled\":true,"
                + "\"url\":\"http://aggregator:9005\","
                + "\"mode\":\"DIRECT\","
                + "\"slowThresholdMs\":321"
                + "}"
                + "}"
                + "}"
                + "}";

        MappingSyncService.PolicySnapshotResponse response =
                objectMapper.readValue(json, MappingSyncService.PolicySnapshotResponse.class);

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        assertNotNull(response.getData().getEndpoint());
        assertEquals("http://engine:9003", response.getData().getEndpoint().getCryptoUrl());
        assertNotNull(response.getData().getEndpoint().getStatsAggregator());
        assertEquals(Boolean.TRUE, response.getData().getEndpoint().getStatsAggregator().getEnabled());
        assertEquals("http://aggregator:9005", response.getData().getEndpoint().getStatsAggregator().getUrl());
        assertEquals("DIRECT", response.getData().getEndpoint().getStatsAggregator().getMode());
        assertEquals(Integer.valueOf(321), response.getData().getEndpoint().getStatsAggregator().getSlowThresholdMs());
    }
}
