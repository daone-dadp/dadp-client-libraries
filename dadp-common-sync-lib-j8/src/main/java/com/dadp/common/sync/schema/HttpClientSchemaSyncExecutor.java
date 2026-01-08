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
    private final String apiBasePath;  // "/hub/api/v1/proxy" 또는 "/hub/api/v1/aop"
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;
    
    public HttpClientSchemaSyncExecutor(String hubUrl, String apiBasePath, HttpClientAdapter httpClient) {
        this.hubUrl = hubUrl;
        this.apiBasePath = apiBasePath;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    
    @Override
    public boolean syncToHub(List<SchemaMetadata> schemas, String hubId, String instanceId, Long currentVersion) throws Exception {
        String syncUrl = hubUrl + apiBasePath + "/schema/sync";
        log.debug("🔗 Hub 스키마 동기화 URL: {}", syncUrl);
        
        SchemaSyncRequest request = new SchemaSyncRequest();
        // 스키마 동기화: 헤더에 hubId를 넣고 body에 스키마만 전송
        // hubId가 없으면 재등록을 위해 body에 instanceId(별칭) 포함
        if (hubId == null || hubId.trim().isEmpty()) {
            // hubId가 없으면 재등록을 위해 별칭(instanceId)를 body에 포함
            request.setInstanceId(instanceId);
        }
        // hubId는 헤더(X-DADP-TENANT)로 전송
        request.setSchemas(schemas);
        
        String requestBody = objectMapper.writeValueAsString(request);
        
        // 헤더에 hubId와 버전 포함 (Hub가 hubId를 헤더에서도 받을 수 있도록)
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (hubId != null && !hubId.trim().isEmpty()) {
            headers.put("X-DADP-TENANT", hubId);  // Hub가 헤더에서 hubId를 받을 수 있도록
        }
        if (currentVersion != null) {
            headers.put("X-Current-Version", String.valueOf(currentVersion));
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
        
        // 404 Not Found: hubId를 찾을 수 없음 (등록되지 않은 hubId) -> 재등록 필요
        // Hub가 instanceId(별칭)와 datasourceId를 받으면 자동으로 재등록을 시도하므로,
        // 클라이언트는 재요청을 통해 재등록된 새로운 hubId를 받을 수 있음
        if (statusCode == 404) {
            // hubId가 있어도 Hub에서 제거되었을 수 있으므로, 재등록 시도
            // 재등록 시에는 hubId가 없는 것처럼 instanceId(별칭)를 사용
            String alias = instanceId; // instanceId는 별칭(alias)임
            String datasourceIdFromSchema = null;
            if (schemas != null && !schemas.isEmpty()) {
                datasourceIdFromSchema = schemas.get(0).getDatasourceId();
            }
            
            if (alias != null && !alias.trim().isEmpty() && 
                datasourceIdFromSchema != null && !datasourceIdFromSchema.trim().isEmpty()) {
                log.info("🔄 Hub에서 hubId 제거됨 (구 hubId), 재등록 시도: alias={}, datasourceId={}", alias, datasourceIdFromSchema);
                
                // 재등록 요청: hubId가 없는 것처럼 처리 (헤더에서 hubId 제거)
                SchemaSyncRequest retryRequest = new SchemaSyncRequest();
                retryRequest.setInstanceId(alias);  // 별칭 사용
                retryRequest.setHubId(null);  // 재등록 시 hubId는 null
                retryRequest.setSchemas(schemas);
                
                // 재등록 시에는 헤더에서 hubId 제거 (hubId가 없는 것처럼 처리)
                Map<String, String> retryHeaders = new HashMap<>();
                retryHeaders.put("Content-Type", "application/json");
                if (currentVersion != null) {
                    retryHeaders.put("X-Current-Version", String.valueOf(currentVersion));
                }
                // X-DADP-TENANT 헤더는 제거 (hubId가 없는 것처럼 처리)
                
                String retryRequestBody = objectMapper.writeValueAsString(retryRequest);
                HttpClientAdapter.HttpResponse retryResponse = httpClient.post(uri, retryRequestBody, retryHeaders);
                int retryStatusCode = retryResponse.getStatusCode();
                String retryResponseBody = retryResponse.getBody();
                
                if (retryStatusCode >= 200 && retryStatusCode < 300 && retryResponseBody != null) {
                    // 재등록 성공, 정상 응답 처리
                    Map<String, Object> retryApiResponse = objectMapper.readValue(retryResponseBody, 
                            objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                    
                    if (retryApiResponse != null && Boolean.TRUE.equals(retryApiResponse.get("success"))) {
                        // 재등록 응답에서 hubId 추출
                        String receivedHubId = extractHubIdFromResponse(retryApiResponse);
                        if (receivedHubId != null && !receivedHubId.trim().isEmpty()) {
                            log.info("✅ Hub에서 재등록 완료 후 스키마 동기화 성공: {}개 컬럼, hubId={}", schemas.size(), receivedHubId);
                            HubIdHolder.setHubId(receivedHubId);
                        } else {
                            log.info("✅ Hub에서 재등록 완료 후 스키마 동기화 성공: {}개 컬럼", schemas.size());
                        }
                        return true;
                    }
                }
            }
            
            // 재등록 실패 또는 alias/datasourceId가 없는 경우
            log.warn("⚠️ Hub로 스키마 메타데이터 동기화 실패: HTTP 400 (재등록 필요)");
            throw new RuntimeException("Hub 스키마 동기화 실패: HTTP 400 - 재등록이 필요합니다. alias=" + instanceId);
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

