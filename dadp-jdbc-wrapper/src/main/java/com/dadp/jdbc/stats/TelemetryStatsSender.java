package com.dadp.jdbc.stats;

import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.http.HttpClientAdapter;
import com.dadp.common.sync.http.Java8HttpClientAdapterFactory;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.net.URI;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Wrapper → Aggregator SQL 이벤트 전송기.
 *
 * 통계 앱 사용 여부가 true이고 statsAggregatorUrl이 존재할 때만 전송한다.
 * 실패 시 DROP (Best-effort).
 */
public class TelemetryStatsSender {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(TelemetryStatsSender.class);

    private final EndpointStorage endpointStorage;
    private final ObjectMapper objectMapper;
    private final String appId;
    private final String datasourceId;
    private final ScheduledExecutorService scheduler;
    private final LinkedBlockingQueue<SqlEvent> buffer;
    private final Random random = new Random();

    // 옵션 (기본값)
    private final int bufferMaxEvents;
    private final int flushMaxEvents;
    private final int flushIntervalMillis;
    private final int maxBatchSize;
    private final int maxPayloadBytes;
    private final double samplingRate;
    private final boolean includeSqlNormalized;
    private final boolean includeParams;
    private final boolean normalizeSqlEnabled;
    private final int httpConnectTimeoutMillis;
    private final int httpReadTimeoutMillis;
    private final int retryOnFailure;

    public TelemetryStatsSender(EndpointStorage endpointStorage, String appId, String datasourceId) {
        this.endpointStorage = endpointStorage;
        // appId(hubId)가 null이면 통계 전송 비활성화 (X-DADP-TENANT 헤더 필수)
        this.appId = appId != null && !appId.trim().isEmpty() ? appId : null;
        this.datasourceId = datasourceId;
        this.objectMapper = new ObjectMapper();
        // Java Time 직렬화 지원
        this.objectMapper.findAndRegisterModules();
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        EndpointStorage.EndpointData data = endpointStorage.loadEndpoints();
        this.bufferMaxEvents = getOrDefault(data != null ? data.getBufferMaxEvents() : null, 10_000);
        this.flushMaxEvents = getOrDefault(data != null ? data.getFlushMaxEvents() : null, 200);
        this.flushIntervalMillis = getOrDefault(data != null ? data.getFlushIntervalMillis() : null, 5000);
        this.maxBatchSize = getOrDefault(data != null ? data.getMaxBatchSize() : null, 500);
        this.maxPayloadBytes = getOrDefault(data != null ? data.getMaxPayloadBytes() : null, 1_000_000);
        this.samplingRate = getOrDefault(data != null ? data.getSamplingRate() : null, 1.0d);
        this.includeSqlNormalized = getOrDefault(data != null ? data.getIncludeSqlNormalized() : null, false);
        this.includeParams = getOrDefault(data != null ? data.getIncludeParams() : null, false);
        this.normalizeSqlEnabled = getOrDefault(data != null ? data.getNormalizeSqlEnabled() : null, true);
        this.httpConnectTimeoutMillis = getOrDefault(data != null ? data.getHttpConnectTimeoutMillis() : null, 200);
        this.httpReadTimeoutMillis = getOrDefault(data != null ? data.getHttpReadTimeoutMillis() : null, 800);
        this.retryOnFailure = getOrDefault(data != null ? data.getRetryOnFailure() : null, 0);

        this.buffer = new LinkedBlockingQueue<>(this.bufferMaxEvents);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telemetry-flush");
            t.setDaemon(true);
            return t;
        });
        // 주기적 플러시
        this.scheduler.scheduleAtFixedRate(this::flushAsync, flushIntervalMillis, flushIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 단일 SQL 실행 이벤트를 배치 형태로 전송.
     *
     * @param sql        원본 SQL
     * @param sqlType    SQL 타입 (SELECT/INSERT/UPDATE/DELETE/DDL/UNKNOWN)
     * @param durationMs 실행 시간(ms)
     * @param errorFlag  오류 여부
     */
    public void sendSqlEvent(String sql, String sqlType, long durationMs, boolean errorFlag) {
        try {
            // hubId(appId)가 없으면 통계 전송 불가 (X-DADP-TENANT 헤더 필수)
            if (appId == null || appId.trim().isEmpty()) {
                log.warn("⚠️ hubId가 없어 통계 이벤트를 건너뜁니다 (X-DADP-TENANT 헤더 필수)");
                return;
            }
            
            EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
            if (endpointData == null) {
                return;
            }

            Boolean enabled = endpointData.getStatsAggregatorEnabled();
            String aggregatorUrl = endpointData.getStatsAggregatorUrl();
            if (enabled == null || !enabled || aggregatorUrl == null || aggregatorUrl.trim().isEmpty()) {
                return;
            }

            // 샘플링
            if (samplingRate < 1.0 && random.nextDouble() > samplingRate) {
                return;
            }

            SqlEvent event = buildEvent(sql, sqlType, durationMs, errorFlag);
            if (!buffer.offer(event)) {
                // overflow -> DROP
                return;
            }

            if (buffer.size() >= flushMaxEvents) {
                flushAsync();
            }

        } catch (Exception e) {
            log.warn("⚠️ 통계 전송 중 예외(DROP): {}", e.getMessage());
        }
    }

    private SqlEvent buildEvent(String sql, String sqlType, long durationMs, boolean errorFlag) {
        String batchId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        String normalizedSqlType = normalizeSqlType(sqlType);
        String sqlHash = sha256(sql != null ? sql : "");

        SqlEvent event = new SqlEvent();
        event.batchId = batchId;
        event.eventId = eventId;
        event.occurredAt = LocalDateTime.now();
        event.sqlHash = sqlHash;
        event.operation = normalizedSqlType;
        event.durationMs = durationMs;
        event.errorFlag = errorFlag;
        event.sqlNormalized = includeSqlNormalized ? sql : null;
        event.sql = sql;
        return event;
    }

    private String normalizeSqlType(String sqlType) {
        if (sqlType == null) {
            return "DDL";
        }
        String upper = sqlType.toUpperCase();
        switch (upper) {
            case "SELECT":
            case "INSERT":
            case "UPDATE":
            case "DELETE":
            case "DDL":
                return upper;
            default:
                return "DDL";
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private void flushAsync() {
        scheduler.execute(this::flush);
    }

    private void flush() {
        try {
            // 버퍼가 비어있으면 엔드포인트 정보를 로드하지 않음
            if (buffer.isEmpty()) {
                return;
            }

            EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
            if (endpointData == null) {
                buffer.clear();
                return;
            }

            Boolean enabled = endpointData.getStatsAggregatorEnabled();
            String aggregatorUrl = endpointData.getStatsAggregatorUrl();
            if (enabled == null || !enabled || aggregatorUrl == null || aggregatorUrl.trim().isEmpty()) {
                buffer.clear();
                return;
            }
            
            aggregatorUrl = aggregatorUrl.trim();

            List<SqlEvent> batch = new ArrayList<>();
            int payloadBytes = 0;
            while (batch.size() < maxBatchSize && !buffer.isEmpty()) {
                SqlEvent ev = buffer.peek();
                if (ev == null) break;
                int estimated = estimateSize(ev);
                if (payloadBytes + estimated > maxPayloadBytes) {
                    break;
                }
                buffer.poll();
                batch.add(ev);
                payloadBytes += estimated;
            }

            if (batch.isEmpty()) {
                return;
            }

            Map<String, Object> body = new HashMap<>();
            body.put("batchId", batch.get(0).batchId);
            body.put("sourceType", "WRAPPER");
            body.put("sourceId", appId);

            // slow_threshold_ms 조회 (endpointData에서)
            Integer slowThresholdMs = endpointData.getSlowThresholdMs();
            
            List<Map<String, Object>> events = new ArrayList<>();
            for (SqlEvent ev : batch) {
                Map<String, Object> e = new HashMap<>();
                e.put("eventId", ev.eventId);
                e.put("eventType", "SQL_EXEC");
                e.put("occurredAt", ev.occurredAt);
                e.put("datasourceId", datasourceId);
                e.put("sqlHash", ev.sqlHash);
                e.put("operation", ev.operation);
                e.put("durationMs", ev.durationMs);
                e.put("rows", null);
                e.put("errorFlag", ev.errorFlag);
                e.put("errorCode", null);
                e.put("slowThresholdMs", slowThresholdMs);
                if (includeSqlNormalized && ev.sql != null) {
                    e.put("sqlNormalized", normalizeSqlEnabled ? ev.sql : ev.sql);
                } else {
                    e.put("sqlNormalized", null);
                }
                if (includeParams) {
                    e.put("params", null); // 마스킹 없이 전송 금지 (추후 구현)
                }
                events.add(e);
            }
            body.put("events", events);

            String ingestUrl = aggregatorUrl + "/aggregator/api/v1/events/batch";

            Map<String, String> headers = new HashMap<>();
            headers.put("X-DADP-TENANT", appId);

            // Java 8용 HTTP 클라이언트 사용 (공통 인터페이스)
            HttpClientAdapter client = Java8HttpClientAdapterFactory.create(httpConnectTimeoutMillis, httpReadTimeoutMillis);

            // 다른 HTTP 요청들과 동일하게 URI.create()를 직접 사용
            URI uri;
            try {
                uri = URI.create(ingestUrl);
            } catch (IllegalArgumentException e) {
                log.error("❌ URI 생성 실패: ingestUrl={}, error={}", ingestUrl, e.getMessage());
                buffer.clear();
                return;
            }
            
            String requestBody = objectMapper.writeValueAsString(body);
            String uriString = uri.toString();
            
            // URI 스키마 검증 (htttp:// 같은 오타 방지)
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                log.error("❌ 잘못된 URI 스키마: scheme={}, uri={}", scheme, uriString);
                buffer.clear();
                return;
            }
            
            boolean success = false;
            try {
                HttpClientAdapter.HttpResponse response = client.post(uri, requestBody, headers);
                int status = response.getStatusCode();
                
                if (status >= 200 && status < 300) {
                    log.info("📡 통계 전송 성공: status={}, url={}", status, uriString);
                    success = true;
                } else {
                    // WARN 로그: uri.toString()을 직접 사용하여 실제 요청 URL과 동일하게
                    // uriString 변수 대신 uri.toString()을 직접 사용하여 로그 포맷팅 오류 방지
                    String actualRequestUrl = uri.toString();
                    log.warn("⚠️ 통계 전송 실패: status={}, url={}, body={}", 
                            status, actualRequestUrl, response.getBody());
                }
            } catch (Exception e) {
                // WARN 로그: uri.toString()을 직접 사용하여 실제 요청 URL과 동일하게
                // uriString 변수 대신 uri.toString()을 직접 사용하여 로그 포맷팅 오류 방지
                String actualRequestUrl = uri.toString();
                log.warn("⚠️ 통계 전송 IO 실패(DROP): url={}, error={}", 
                        actualRequestUrl, e.getMessage());
            }
            
            // 재시도 로직 제거: Best-effort 정책에 따라 실패 시 즉시 DROP

        } catch (Exception e) {
            // DROP on failure
            log.warn("⚠️ 통계 플러시 실패(DROP): {}", e.getMessage());
        }
    }

    private int estimateSize(SqlEvent ev) {
        // 매우 단순한 추정치 (필드 문자열 길이 합산)
        int size = 0;
        size += len(ev.eventId);
        size += len(ev.sqlHash);
        size += len(ev.operation);
        size += len(ev.sqlNormalized);
        size += len(ev.sql);
        size += 64; // 기타 필드 여유
        return size;
    }

    private int len(String s) {
        return s == null ? 0 : s.length();
    }

    private int getOrDefault(Integer v, int def) {
        return v != null ? v : def;
    }

    private double getOrDefault(Double v, double def) {
        return v != null ? v : def;
    }

    private boolean getOrDefault(Boolean v, boolean def) {
        return v != null ? v : def;
    }

    private static class SqlEvent {
        String batchId;
        String eventId;
        LocalDateTime occurredAt;
        String sqlHash;
        String operation;
        long durationMs;
        boolean errorFlag;
        String sqlNormalized;
        String sql;
    }
}

