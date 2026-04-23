package com.dadp.hub.crypto;

import com.dadp.common.sync.crypto.CryptoProfileRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional NDJSON recorder for wrapper-side crypto stage profiling.
 *
 * <p>This recorder is created only when profiling is explicitly enabled.
 * The default wrapper path does not instantiate or write through this component.</p>
 */
public class WrapperCryptoProfileRecorder implements CryptoProfileRecorder {

    private static final Logger log = LoggerFactory.getLogger(WrapperCryptoProfileRecorder.class);

    private final Path outputPath;
    private final String instanceId;
    private final String hubId;
    private final String datasourceId;
    private final ObjectMapper objectMapper;

    public WrapperCryptoProfileRecorder(String outputPath, String instanceId, String hubId, String datasourceId) {
        this.outputPath = Paths.get(outputPath);
        this.instanceId = instanceId;
        this.hubId = hubId;
        this.datasourceId = datasourceId;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void record(Map<String, Object> event) {
        if (event == null) {
            return;
        }

        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("timestamp", System.currentTimeMillis());
            line.put("instanceId", instanceId);
            line.put("hubId", hubId);
            line.put("datasourceId", datasourceId);
            line.putAll(event);

            try (BufferedWriter writer = Files.newBufferedWriter(
                    outputPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                writer.write(objectMapper.writeValueAsString(line));
                writer.newLine();
            }
        } catch (IOException e) {
            log.warn("Wrapper crypto profile write failed: path={}, error={}", outputPath, e.getMessage());
        }
    }

    public void record(OperationEvent event) {
        if (event != null) {
            record(event.toMap());
        }
    }

    public static final class OperationEvent {
        final String operation;
        final String endpoint;
        final Long requestId;
        final Integer statusCode;
        final boolean success;
        final String policyName;
        final String maskPolicyName;
        final Integer requestBytes;
        final Integer responseBytes;
        final String wrapperTransportMode;
        final Double connectionOpenMs;
        final Double outputStreamOpenMs;
        final Double bodyWriteMs;
        final Double bodyFlushCloseMs;
        final Double requestBuildMs;
        final Double httpWriteMs;
        final Double httpResponseCodeMs;
        final Double httpReadMs;
        final Double responseParseMs;
        final Double totalMs;
        final Long engineProcessingTimeMs;
        final String engineTraceId;
        final Map<String, Object> engineStats;
        final String error;

        private OperationEvent(Builder builder) {
            this.operation = builder.operation;
            this.endpoint = builder.endpoint;
            this.requestId = builder.requestId;
            this.statusCode = builder.statusCode;
            this.success = builder.success;
            this.policyName = builder.policyName;
            this.maskPolicyName = builder.maskPolicyName;
            this.requestBytes = builder.requestBytes;
            this.responseBytes = builder.responseBytes;
            this.wrapperTransportMode = builder.wrapperTransportMode;
            this.connectionOpenMs = builder.connectionOpenMs;
            this.outputStreamOpenMs = builder.outputStreamOpenMs;
            this.bodyWriteMs = builder.bodyWriteMs;
            this.bodyFlushCloseMs = builder.bodyFlushCloseMs;
            this.requestBuildMs = builder.requestBuildMs;
            this.httpWriteMs = builder.httpWriteMs;
            this.httpResponseCodeMs = builder.httpResponseCodeMs;
            this.httpReadMs = builder.httpReadMs;
            this.responseParseMs = builder.responseParseMs;
            this.totalMs = builder.totalMs;
            this.engineProcessingTimeMs = builder.engineProcessingTimeMs;
            this.engineTraceId = builder.engineTraceId;
            this.engineStats = builder.engineStats;
            this.error = builder.error;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("operation", operation);
            line.put("endpoint", endpoint);
            line.put("requestId", requestId);
            line.put("statusCode", statusCode);
            line.put("success", success);
            line.put("policyName", policyName);
            line.put("maskPolicyName", maskPolicyName);
            line.put("requestBytes", requestBytes);
            line.put("responseBytes", responseBytes);
            line.put("wrapperTransportMode", wrapperTransportMode);
            line.put("connectionOpenMs", connectionOpenMs);
            line.put("outputStreamOpenMs", outputStreamOpenMs);
            line.put("bodyWriteMs", bodyWriteMs);
            line.put("bodyFlushCloseMs", bodyFlushCloseMs);
            line.put("requestBuildMs", requestBuildMs);
            line.put("httpWriteMs", httpWriteMs);
            line.put("httpResponseCodeMs", httpResponseCodeMs);
            line.put("httpReadMs", httpReadMs);
            line.put("responseParseMs", responseParseMs);
            line.put("totalMs", totalMs);
            line.put("engineProcessingTimeMs", engineProcessingTimeMs);
            line.put("engineTraceId", engineTraceId);
            line.put("engineStats", engineStats);
            line.put("error", error);
            return line;
        }

        public static Builder builder(String operation) {
            return new Builder(operation);
        }

        public static final class Builder {
            private final String operation;
            private String endpoint;
            private Long requestId;
            private Integer statusCode;
            private boolean success;
            private String policyName;
            private String maskPolicyName;
            private Integer requestBytes;
            private Integer responseBytes;
            private String wrapperTransportMode;
            private Double connectionOpenMs;
            private Double outputStreamOpenMs;
            private Double bodyWriteMs;
            private Double bodyFlushCloseMs;
            private Double requestBuildMs;
            private Double httpWriteMs;
            private Double httpResponseCodeMs;
            private Double httpReadMs;
            private Double responseParseMs;
            private Double totalMs;
            private Long engineProcessingTimeMs;
            private String engineTraceId;
            private Map<String, Object> engineStats;
            private String error;

            private Builder(String operation) {
                this.operation = operation;
            }

            public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
            public Builder requestId(Long requestId) { this.requestId = requestId; return this; }
            public Builder statusCode(Integer statusCode) { this.statusCode = statusCode; return this; }
            public Builder success(boolean success) { this.success = success; return this; }
            public Builder policyName(String policyName) { this.policyName = policyName; return this; }
            public Builder maskPolicyName(String maskPolicyName) { this.maskPolicyName = maskPolicyName; return this; }
            public Builder requestBytes(Integer requestBytes) { this.requestBytes = requestBytes; return this; }
            public Builder responseBytes(Integer responseBytes) { this.responseBytes = responseBytes; return this; }
            public Builder wrapperTransportMode(String wrapperTransportMode) { this.wrapperTransportMode = wrapperTransportMode; return this; }
            public Builder connectionOpenMs(Double connectionOpenMs) { this.connectionOpenMs = connectionOpenMs; return this; }
            public Builder outputStreamOpenMs(Double outputStreamOpenMs) { this.outputStreamOpenMs = outputStreamOpenMs; return this; }
            public Builder bodyWriteMs(Double bodyWriteMs) { this.bodyWriteMs = bodyWriteMs; return this; }
            public Builder bodyFlushCloseMs(Double bodyFlushCloseMs) { this.bodyFlushCloseMs = bodyFlushCloseMs; return this; }
            public Builder requestBuildMs(Double requestBuildMs) { this.requestBuildMs = requestBuildMs; return this; }
            public Builder httpWriteMs(Double httpWriteMs) { this.httpWriteMs = httpWriteMs; return this; }
            public Builder httpResponseCodeMs(Double httpResponseCodeMs) { this.httpResponseCodeMs = httpResponseCodeMs; return this; }
            public Builder httpReadMs(Double httpReadMs) { this.httpReadMs = httpReadMs; return this; }
            public Builder responseParseMs(Double responseParseMs) { this.responseParseMs = responseParseMs; return this; }
            public Builder totalMs(Double totalMs) { this.totalMs = totalMs; return this; }
            public Builder engineProcessingTimeMs(Long engineProcessingTimeMs) { this.engineProcessingTimeMs = engineProcessingTimeMs; return this; }
            public Builder engineTraceId(String engineTraceId) { this.engineTraceId = engineTraceId; return this; }
            public Builder engineStats(Map<String, Object> engineStats) { this.engineStats = engineStats; return this; }
            public Builder error(String error) { this.error = error; return this; }

            public OperationEvent build() {
                return new OperationEvent(this);
            }
        }
    }
}
