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
        request.setInstanceId(hubId);  // hubId를 instanceId로 사용
        request.setHubId(hubId);  // AOP 호환성을 위해 hubId도 설정
        request.setSchemas(schemas);
        
        String requestBody = objectMapper.writeValueAsString(request);
        
        // 헤더에 버전 포함 (버전 동기화용)
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
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
        
        if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
            // ApiResponse 래퍼 파싱
            Map<String, Object> apiResponse = objectMapper.readValue(responseBody, 
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            
            if (apiResponse != null && Boolean.TRUE.equals(apiResponse.get("success"))) {
                log.info("✅ Hub로 스키마 메타데이터 동기화 완료: {}개 컬럼", schemas.size());
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
}

