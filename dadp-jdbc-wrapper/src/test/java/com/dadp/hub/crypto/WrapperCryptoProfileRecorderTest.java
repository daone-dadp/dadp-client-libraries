package com.dadp.hub.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WrapperCryptoProfileRecorderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void recorderWritesSingleNdjsonLine() throws Exception {
        Path outputPath = tempDir.resolve("wrapper-profile.ndjson");
        WrapperCryptoProfileRecorder recorder = new WrapperCryptoProfileRecorder(
                outputPath.toString(),
                "instance-1",
                "hub-1",
                "datasource-1");

        recorder.record(WrapperCryptoProfileRecorder.OperationEvent.builder("decrypt")
                .endpoint("/api/decrypt")
                .requestId(101L)
                .statusCode(200)
                .success(true)
                .policyName("policy-user-name")
                .requestBytes(128)
                .responseBytes(256)
                .wrapperTransportMode("binary-framed")
                .connectionOpenMs(0.2)
                .outputStreamOpenMs(0.0)
                .bodyWriteMs(0.3)
                .bodyFlushCloseMs(0.1)
                .requestBuildMs(0.5)
                .httpWriteMs(1.2)
                .httpResponseCodeMs(2.3)
                .httpReadMs(3.4)
                .responseParseMs(0.7)
                .totalMs(8.1)
                .engineProcessingTimeMs(4L)
                .engineTraceId("trace-123")
                .engineStats(Collections.singletonMap("cryptoOperationTime", 4))
                .build());

        assertTrue(Files.exists(outputPath));

        String line = new String(Files.readAllBytes(outputPath), StandardCharsets.UTF_8).trim();
        JsonNode root = objectMapper.readTree(line);
        assertEquals("instance-1", root.get("instanceId").asText());
        assertEquals("decrypt", root.get("operation").asText());
        assertEquals(101L, root.get("requestId").asLong());
        assertEquals("trace-123", root.get("engineTraceId").asText());
        assertEquals("binary-framed", root.get("wrapperTransportMode").asText());
        assertEquals(0.3, root.get("bodyWriteMs").asDouble(), 0.0001);
    }
}
