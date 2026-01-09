package com.dadp.jdbc.schema;

import com.dadp.common.sync.http.HttpClientAdapter;
import com.dadp.common.sync.http.Java8HttpClientAdapterFactory;
import com.dadp.jdbc.policy.SchemaRecognizer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

/**
 * 스키마 동기화 서비스
 * 
 * Proxy에서 Hub로 스키마 메타데이터를 전송합니다.
 * Java 버전에 따라 적절한 HTTP 클라이언트를 자동으로 선택합니다.
 * 
 * @author DADP Development Team
 * @version 3.0.5
 * @since 2025-11-07
 */
public class SchemaSyncService {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(SchemaSyncService.class);
    
    private final String hubUrl;
    private final String hubId;  // Hub가 발급한 고유 ID (X-DADP-TENANT 헤더에 사용)
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;
    private final SchemaRecognizer schemaRecognizer;
    
    // Proxy Instance별 마지막 동기화된 스키마 해시 (중복 동기화 방지)
    private static final ConcurrentHashMap<String, String> lastSchemaHash = new ConcurrentHashMap<>();
    
    public SchemaSyncService(String hubUrl, String hubId, String alias) {
        this.hubUrl = hubUrl;
        this.hubId = hubId;
        // Java 8용 HTTP 클라이언트 사용 (공통 인터페이스)
        this.httpClient = Java8HttpClientAdapterFactory.create(5000, 10000);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.schemaRecognizer = new SchemaRecognizer();
    }
    
    /**
     * 스키마 메타데이터를 Hub로 동기화
     * 
     * 스키마가 변경되지 않았으면 동기화를 건너뜁니다 (중복 동기화 방지).
     * 
     * @param connection DB 연결
     * @param datasourceId Datasource ID (null 가능)
     * @param currentVersion 현재 버전 (버전 동기화용, null 가능)
     */
    public void syncSchemaToHub(Connection connection, String datasourceId, Long currentVersion) {
        try {
            log.trace("🔄 Hub로 스키마 메타데이터 동기화 시작: hubId={}, datasourceId={}", 
                hubId, datasourceId);
            
            // 스키마 메타데이터 수집
            List<SchemaRecognizer.SchemaMetadata> schemas = schemaRecognizer.collectSchemaMetadata(connection, datasourceId);
            
            // 스키마 해시 계산 (변경 감지용)
            String currentHash = calculateSchemaHash(schemas);
            String lastHash = lastSchemaHash.get(hubId);
            
            // 스키마가 변경되지 않았으면 동기화 건너뛰기
            if (lastHash != null && currentHash.equals(lastHash)) {
                log.trace("⏭️ 스키마 변경 없음, 동기화 건너뜀: hubId={} (해시: {})", 
                        hubId, currentHash.substring(0, 8) + "...");
                return;
            }
            
            log.info("📤 스키마 변경 감지, Hub로 동기화 전송: {}개 컬럼", schemas.size());
            
            // Hub API로 전송
            // V1 API 사용: /hub/api/v1/proxy/schema/sync
            String syncPath = "/hub/api/v1/proxy/schema/sync";
            String syncUrl = hubUrl + syncPath;
            log.debug("🔗 Hub 스키마 동기화 URL: {}", syncUrl);
            
            SchemaSyncRequest request = new SchemaSyncRequest();
            request.setProxyInstanceId(hubId);  // hubId를 사용
            request.setSchemas(schemas);
            
            String requestBody = objectMapper.writeValueAsString(request);
            
            // 헤더에 버전 포함 (버전 동기화용)
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Content-Type", "application/json");
            if (currentVersion != null) {
                headers.put("X-Current-Version", String.valueOf(currentVersion));
            }
            
            // Java 버전에 따라 적절한 HTTP 클라이언트 사용
            URI uri = URI.create(syncUrl);
            HttpClientAdapter.HttpResponse response = httpClient.post(uri, requestBody, headers);
            
            int statusCode = response.getStatusCode();
            String responseBody = response.getBody();
            
            // 304 Not Modified 처리 (버전이 같으면 스키마 데이터 없이 반환)
            if (statusCode == 304) {
                log.debug("✅ 스키마 동기화 불필요 (304): 버전이 동일함, currentVersion={}", currentVersion);
                return;
            }
            
            if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
                // ApiResponse 래퍼 파싱
                java.util.Map<String, Object> apiResponse = objectMapper.readValue(responseBody, 
                        objectMapper.getTypeFactory().constructMapType(java.util.Map.class, String.class, Object.class));
                
                if (apiResponse != null && Boolean.TRUE.equals(apiResponse.get("success"))) {
                    // 동기화 성공 시 해시 저장
                    lastSchemaHash.put(hubId, currentHash);
                    
                    log.info("✅ Hub로 스키마 메타데이터 동기화 완료: {}개 컬럼 (해시: {})", 
                            schemas.size(), currentHash.substring(0, 8) + "...");
                } else {
                    log.warn("⚠️ Hub로 스키마 메타데이터 동기화 실패: 응답 없음");
                    // TODO: 알림 기능은 문서 작업 후 구현
                    // handleSyncFailure("Hub 응답 없음");
                }
            } else {
                log.warn("⚠️ Hub로 스키마 메타데이터 동기화 실패: HTTP {}", statusCode);
                // TODO: 알림 기능은 문서 작업 후 구현
                // handleSyncFailure("HTTP " + statusCode);
            }
            
        } catch (Exception e) {
            // 연결 실패는 예측 가능한 문제이므로 WARN 레벨로 처리 (정책 준수)
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (errorMsg.contains("Connection refused") || errorMsg.contains("ConnectException")) {
                log.warn("⚠️ Hub로 스키마 메타데이터 동기화 실패: {} (Hub 연결 불가)", errorMsg);
            } else {
                // 예측 불가능한 문제만 ERROR로 처리
                log.error("❌ Hub로 스키마 메타데이터 동기화 실패: {}", errorMsg, e);
            }
            // Hub 통신 장애는 알림 제거 (받는 주체가 Hub이므로)
            // 동기화 실패해도 계속 진행 (Fail-open)
        }
    }
    
    /**
     * 스키마 메타데이터의 해시값 계산
     * 
     * 스키마 변경 감지를 위해 사용합니다.
     * 
     * @param schemas 스키마 메타데이터 목록
     * @return 해시값 (SHA-256)
     */
    private String calculateSchemaHash(List<SchemaRecognizer.SchemaMetadata> schemas) {
        try {
            // 스키마를 문자열로 직렬화
            StringBuilder sb = new StringBuilder();
            for (SchemaRecognizer.SchemaMetadata schema : schemas) {
                sb.append(schema.getDatabaseName()).append("|");
                sb.append(schema.getTableName()).append("|");
                sb.append(schema.getColumnName()).append("|");
                sb.append(schema.getColumnType()).append("|");
                sb.append(schema.getIsNullable()).append("|");
                sb.append(schema.getColumnDefault()).append("\n");
            }
            
            // SHA-256 해시 계산
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(sb.toString().getBytes("UTF-8"));
            
            // 16진수 문자열로 변환
            StringBuilder hashString = new StringBuilder();
            for (byte b : hashBytes) {
                hashString.append(String.format("%02x", b));
            }
            
            return hashString.toString();
        } catch (Exception e) {
            log.warn("⚠️ 스키마 해시 계산 실패, 기본값 사용: {}", e.getMessage());
            // 해시 계산 실패 시 타임스탬프 사용 (항상 변경된 것으로 간주)
            return String.valueOf(System.currentTimeMillis());
        }
    }
    
    /**
     * 스키마 해시 캐시 초기화 (강제 동기화 시 사용)
     */
    public void clearSchemaHash() {
        lastSchemaHash.remove(hubId);
        log.info("🧹 스키마 해시 캐시 초기화: hubId={}", hubId);
    }
    
    /**
     * 스키마 동기화 요청 DTO
     */
    public static class SchemaSyncRequest {
        private String proxyInstanceId;
        private List<SchemaRecognizer.SchemaMetadata> schemas;
        private Long currentVersion;  // 버전 동기화용
        
        public String getProxyInstanceId() {
            return proxyInstanceId;
        }
        
        public void setProxyInstanceId(String proxyInstanceId) {
            this.proxyInstanceId = proxyInstanceId;
        }
        
        public List<SchemaRecognizer.SchemaMetadata> getSchemas() {
            return schemas;
        }
        
        public void setSchemas(List<SchemaRecognizer.SchemaMetadata> schemas) {
            this.schemas = schemas;
        }
        
        public Long getCurrentVersion() {
            return currentVersion;
        }
        
        public void setCurrentVersion(Long currentVersion) {
            this.currentVersion = currentVersion;
        }
    }
    
}
