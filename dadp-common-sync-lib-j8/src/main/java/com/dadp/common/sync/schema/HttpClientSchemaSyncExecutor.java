package com.dadp.common.sync.schema;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.common.sync.http.HttpClientAdapter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HttpClientAdapter 기반 스키마 동기화 실행 구현체 (Java 8/17 공통)
 * 
 * Wrapper에서 사용하는 HttpClientAdapter 기반 구현입니다.
 * 
 * @author DADP Development Team
 * @version 5.1.0
 * @since 2026-01-06
 */
public class HttpClientSchemaSyncExecutor implements SchemaSyncExecutor {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(HttpClientSchemaSyncExecutor.class);
    
    private final String hubUrl;
    private final String apiBasePath;  // "/hub/api/v1/aop" 또는 "/hub/api/v1/proxy"
    private final String instanceType;  // "PROXY" 또는 "AOP" (새 API 사용 시 필수)
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;
    
    public HttpClientSchemaSyncExecutor(String hubUrl, String apiBasePath, HttpClientAdapter httpClient) {
        this(hubUrl, apiBasePath, null, httpClient);
    }
    
    public HttpClientSchemaSyncExecutor(String hubUrl, String apiBasePath, String instanceType, HttpClientAdapter httpClient) {
        this.hubUrl = hubUrl;
        this.apiBasePath = apiBasePath;
        this.instanceType = instanceType;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    
    @Override
    public boolean syncToHub(List<SchemaMetadata> schemas, String hubId, String instanceId, Long currentVersion) throws Exception {
        // AOP는 복수형(/schemas/sync), Wrapper는 단수형(/schema/sync)
        boolean isAop = apiBasePath != null && apiBasePath.contains("/aop");
        String syncPath = isAop ? "/schemas/sync" : "/schema/sync";
        String syncUrl = hubUrl + apiBasePath + syncPath;
        log.debug("🔗 Hub 스키마 동기화 URL: {}", syncUrl);
        
        SchemaSyncRequest request = new SchemaSyncRequest();
        // 스키마 동기화: 헤더에 hubId와 instanceId를 넣고 body에 instanceId와 스키마 전송
        // hubId는 헤더(X-DADP-TENANT)로 전송
        // instanceId는 헤더(X-Instance-Id)와 body에 모두 포함
        request.setInstanceId(instanceId);
        request.setSchemas(schemas);
        
        String requestBody = objectMapper.writeValueAsString(request);
        
        // 헤더에 hubId, instanceId(별칭), 버전, instanceType 포함 (새 API 사용 시)
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (hubId != null && !hubId.trim().isEmpty()) {
            headers.put("X-DADP-TENANT", hubId);  // Hub가 헤더에서 hubId를 받을 수 있도록
        }
        /**
         * X-Instance-Id 헤더 추가
         * 
         * Hub가 헤더에서 instanceId(별칭)를 받아 스키마를 alias로 직접 저장할 수 있도록 함.
         * instanceId는 요청 바디에도 포함되지만, 헤더에서도 전달하여 Hub가 별도 조회 없이 사용할 수 있음.
         * 
         * @param instanceId 인스턴스 별칭 (null이거나 비어있으면 헤더에 추가하지 않음)
         */
        if (instanceId != null && !instanceId.trim().isEmpty()) {
            headers.put("X-Instance-Id", instanceId);
        }
        if (currentVersion != null) {
            headers.put("X-Current-Version", String.valueOf(currentVersion));
        }
        // Wrapper 사용 시 X-Instance-Type 헤더 추가
        if (!isAop && instanceType != null && !instanceType.trim().isEmpty()) {
            headers.put("X-Instance-Type", instanceType);
        }
        
        // 요청 로깅 (디버깅용)
        log.debug("📤 Hub 스키마 동기화 요청: URL={}, hubId={}, 스키마 개수={}", syncUrl, hubId, schemas != null ? schemas.size() : 0);
        log.debug("📤 요청 헤더: {}", headers);
        log.debug("📤 요청 바디: {}", requestBody);
        
        // 각 스키마의 datasourceId 포함 로그 (INFO 레벨)
        if (schemas != null && !schemas.isEmpty()) {
            for (SchemaMetadata schema : schemas) {
                log.info("📤 스키마 전송 데이터: schema={}.{}.{}, datasourceId={}, database={}, dbVendor={}", 
                    schema.getSchemaName(), schema.getTableName(), schema.getColumnName(),
                    schema.getDatasourceId(), schema.getDatabaseName(), schema.getDbVendor());
            }
        }
        
        // HTTP POST 요청
        URI uri = URI.create(syncUrl);
        HttpClientAdapter.HttpResponse response = httpClient.post(uri, requestBody, headers);
        
        int statusCode = response.getStatusCode();
        String responseBody = response.getBody();
        
        // 304 Not Modified 처리 (버전이 같으면 스키마 데이터 없이 반환)
        if (statusCode == 304) {
            log.debug("✅ 스키마 동기화 불필요 (304): 버전이 동일함, currentVersion={}", currentVersion);
            return true;
        }
        
        // 404 Not Found: hubId를 찾을 수 없음 (등록되지 않은 hubId) -> 재등록 필요 (예외가 아닌 정상 응답 코드)
        if (statusCode == 404) {
            log.info("🔄 Hub에서 hubId를 찾을 수 없음 (404): hubId={}, 재등록 필요", hubId);
            // 404는 정상적인 응답 코드이므로 특별한 예외를 던져서 호출하는 쪽에서 재등록 처리
            throw new SchemaSync404Exception("Hub 스키마 동기화 실패: HTTP 404 - hubId를 찾을 수 없습니다. 재등록이 필요합니다. hubId=" + hubId);
        }
        
        if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
            // ApiResponse 래퍼 파싱
            Map<String, Object> apiResponse = objectMapper.readValue(responseBody, 
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            
            if (apiResponse != null && Boolean.TRUE.equals(apiResponse.get("success"))) {
                // 응답에서 hubId 추출 (재등록 시 hubId가 응답에 포함됨)
                String receivedHubId = extractHubIdFromResponse(apiResponse);
                if (receivedHubId != null && !receivedHubId.trim().isEmpty()) {
                    log.info("✅ Hub로 스키마 메타데이터 동기화 완료: {}개 컬럼, hubId={}", schemas.size(), receivedHubId);
                    HubIdHolder.setHubId(receivedHubId);
                } else {
                    log.info("✅ Hub로 스키마 메타데이터 동기화 완료: {}개 컬럼", schemas.size());
                }
                return true;
            } else {
                log.warn("⚠️ Hub로 스키마 메타데이터 동기화 실패: 응답 success=false");
                throw new RuntimeException("Hub 스키마 동기화 실패: 응답 success=false");
            }
        } else {
            log.warn("⚠️ Hub로 스키마 메타데이터 동기화 실패: HTTP {}", statusCode);
            throw new RuntimeException("Hub 스키마 동기화 실패: HTTP " + statusCode + (responseBody != null ? " - " + responseBody : ""));
        }
    }
    
    /**
     * 응답에서 hubId 추출
     * Hub 응답 구조: { "success": true, "data": { "hubId": "...", "success": true } }
     */
    @SuppressWarnings("unchecked")
    private String extractHubIdFromResponse(Map<String, Object> apiResponse) {
        try {
            Object dataObj = apiResponse.get("data");
            if (dataObj instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) dataObj;
                Object hubIdObj = data.get("hubId");
                if (hubIdObj instanceof String) {
                    return (String) hubIdObj;
                }
            }
        } catch (Exception e) {
            log.debug("응답에서 hubId 추출 실패: {}", e.getMessage());
        }
        return null;
    }
    
    @Override
    public String getReceivedHubId() {
        return HubIdHolder.getHubId();
    }
    
    @Override
    public void clearReceivedHubId() {
        HubIdHolder.clear();
    }
}

/**
 * HubId를 ThreadLocal에 저장하여 상위 메서드에서 접근 가능하도록 하는 헬퍼 클래스
 */
class HubIdHolder {
    private static final ThreadLocal<String> hubIdThreadLocal = new ThreadLocal<>();
    
    static void setHubId(String hubId) {
        hubIdThreadLocal.set(hubId);
    }
    
    static String getHubId() {
        return hubIdThreadLocal.get();
    }
    
    static void clear() {
        hubIdThreadLocal.remove();
    }
}

/**
 * 404 응답을 나타내는 예외 (정상적인 응답 코드이지만 재등록이 필요함을 표시)
 */
class SchemaSync404Exception extends RuntimeException {
    SchemaSync404Exception(String message) {
        super(message);
    }
}

