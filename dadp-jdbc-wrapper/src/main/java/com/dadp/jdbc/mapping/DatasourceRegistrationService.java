package com.dadp.jdbc.mapping;

import com.dadp.jdbc.config.DatasourceStorage;
import com.dadp.common.sync.http.HttpClientAdapter;
import com.dadp.common.sync.http.Java8HttpClientAdapterFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Datasource 등록 서비스
 * 
 * Proxy가 DB 물리 정보를 Hub에 전송하여 datasourceId를 받아옵니다.
 * Hub가 죽어 있어도 로컬 저장소에서 이전에 받은 datasourceId를 사용할 수 있습니다.
 * 
 * @author DADP Development Team
 * @version 4.0.0
 * @since 2025-12-05
 */
public class DatasourceRegistrationService {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(DatasourceRegistrationService.class);
    
    private final String hubUrl;
    private final String proxyInstanceId;
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;
    
    public DatasourceRegistrationService(String hubUrl, String proxyInstanceId) {
        this.hubUrl = hubUrl;
        this.proxyInstanceId = proxyInstanceId;
        // Java 8용 HTTP 클라이언트 사용 (공통 인터페이스)
        this.httpClient = Java8HttpClientAdapterFactory.create(5000, 10000);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    
    /**
     * Datasource 등록/조회
     * 
     * 먼저 로컬 저장소에서 확인하고, 없으면 Hub에 등록 요청합니다.
     * Hub 연결 실패 시 null을 반환합니다 (임시 ID 생성 안 함).
     * 
     * @param dbVendor DB 벤더
     * @param host 호스트
     * @param port 포트
     * @param database 데이터베이스명
     * @param schema 스키마명
     * @param currentVersion 현재 버전 (재등록 시 사용, 없으면 0)
     * @return Datasource 정보 (Hub 연결 실패 시 null)
     */
    public DatasourceInfo registerOrGetDatasource(
            String dbVendor, String host, int port, 
            String database, String schema, Long currentVersion) {
        
        // 먼저 로컬 저장소에서 확인
        String cachedDatasourceId = DatasourceStorage.loadDatasourceId(dbVendor, host, port, database, schema);
        if (cachedDatasourceId != null) {
            log.debug("로컬 저장소에서 Datasource ID 발견: {}", cachedDatasourceId);
            // Hub에 재등록 요청 (hubId 받기 위해)
            // 로컬에 datasourceId만 있고 hubId가 없을 수 있으므로 Hub에 재등록 시도
            // Hub 연결 실패 시 로컬 datasourceId만 반환 (hubId는 null)
        }
        
        // V1 API 사용: /hub/api/v1/proxy/datasources/register
        String registerUrl = hubUrl + "/hub/api/v1/proxy/datasources/register";
        
        Map<String, Object> request = new HashMap<>();
        request.put("instanceId", proxyInstanceId);  // 문서에 따르면 "instanceId" 사용
        request.put("dbVendor", dbVendor);
        request.put("host", host);
        request.put("port", port);
        request.put("database", database);
        request.put("schema", schema);
        // 재등록 시 Hub가 hubVersion = currentVersion + 1로 설정할 수 있도록 currentVersion 전송
        request.put("currentVersion", currentVersion != null ? currentVersion : 0L);
        
        try {
            // HTTP POST 요청
            HttpClientAdapter.HttpResponse response = httpClient.post(URI.create(registerUrl), 
                objectMapper.writeValueAsString(request));
            
            int statusCode = response.getStatusCode();
            String responseBody = response.getBody();
            
            log.debug("Hub Datasource 등록 응답: statusCode={}, responseBody={}", statusCode, responseBody);
            
            if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
                try {
                    // 응답 파싱
                    ApiResponse<DatasourceInfo> apiResponse = objectMapper.readValue(
                        responseBody, 
                        new TypeReference<ApiResponse<DatasourceInfo>>() {}
                    );
                    
                    if (apiResponse != null && apiResponse.isSuccess() && apiResponse.getData() != null) {
                        DatasourceInfo info = apiResponse.getData();
                        // 로컬에 저장
                        DatasourceStorage.saveDatasource(info.getDatasourceId(), dbVendor, host, port, database, schema);
                        log.info("✅ Hub에서 Datasource 등록 완료: datasourceId={}, displayName={}, hubId={}", 
                            info.getDatasourceId(), info.getDisplayName(), info.getHubId());
                        return info;
                    } else {
                        log.warn("⚠️ Hub Datasource 등록 실패: 응답 형식 오류. statusCode={}, success={}, data={}, responseBody={}", 
                            statusCode, apiResponse != null ? apiResponse.isSuccess() : "null", 
                            apiResponse != null ? apiResponse.getData() : "null", responseBody);
                    }
                } catch (Exception parseEx) {
                    log.warn("⚠️ Hub Datasource 등록 실패: 응답 파싱 오류. statusCode={}, responseBody={}, error={}", 
                        statusCode, responseBody, parseEx.getMessage());
                }
            } else {
                log.warn("⚠️ Hub Datasource 등록 실패: HTTP 오류. statusCode={}, responseBody={}", statusCode, responseBody);
            }
        } catch (IOException e) {
            log.warn("⚠️ Hub Datasource 등록 실패: hubUrl={}, registerUrl={}, error={}", 
                hubUrl, hubUrl + "/hub/api/v1/proxy/datasources/register", e.getMessage(), e);
            // Hub 통신 장애는 알림 제거 (받는 주체가 Hub이므로)
        }
        
        // Hub 등록 실패 시 null 반환 (임시 ID 생성 안 함)
        // 정책이 없으면 암호화/복호화 대상이 없으므로 평문 그대로 통과
        return null;
    }
    
    
    /**
     * Datasource ID 확인 (비동기)
     * 
     * 로컬 저장소에서 읽은 datasourceId가 여전히 유효한지 Hub에 확인합니다.
     * 실패해도 무시합니다 (로컬 ID를 계속 사용).
     */
    private void verifyDatasourceIdAsync(String datasourceId, String dbVendor, String host, 
                                         int port, String database, String schema) {
        // 비동기로 Hub에 확인 (실패해도 무시)
        new Thread(() -> {
            try {
                // 향후 Hub에 확인 API가 추가되면 여기서 호출
                // 지금은 로컬 ID를 그대로 사용
            } catch (Exception e) {
                log.debug("Datasource ID 확인 실패 (무시): {}", e.getMessage());
            }
        }, "dadp-datasource-verify").start();
    }
    
    /**
     * Display Name 생성
     */
    private String generateDisplayName(String dbVendor, String database, String schema) {
        if (schema != null && !schema.isEmpty() && !schema.equals(database)) {
            return dbVendor + ":" + database + "/" + schema;
        }
        return dbVendor + ":" + database;
    }
    
    /**
     * Datasource 정보 DTO
     */
    public static class DatasourceInfo {
        private String datasourceId;
        private String displayName;
        private String hubId;  // Hub가 발급한 고유 ID (X-DADP-TENANT 헤더에 사용)
        
        public DatasourceInfo() {
        }
        
        public DatasourceInfo(String datasourceId, String displayName) {
            this.datasourceId = datasourceId;
            this.displayName = displayName;
        }
        
        public DatasourceInfo(String datasourceId, String displayName, String hubId) {
            this.datasourceId = datasourceId;
            this.displayName = displayName;
            this.hubId = hubId;
        }
        
        public String getDatasourceId() {
            return datasourceId;
        }
        
        public void setDatasourceId(String datasourceId) {
            this.datasourceId = datasourceId;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
        
        public String getHubId() {
            return hubId;
        }
        
        public void setHubId(String hubId) {
            this.hubId = hubId;
        }
    }
    
    /**
     * API 응답 DTO
     */
    public static class ApiResponse<T> {
        private boolean success;
        private T data;
        private String message;
        
        public boolean isSuccess() {
            return success;
        }
        
        public void setSuccess(boolean success) {
            this.success = success;
        }
        
        public T getData() {
            return data;
        }
        
        public void setData(T data) {
            this.data = data;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
    }
}

