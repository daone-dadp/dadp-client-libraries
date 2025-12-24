package com.dadp.hub.crypto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.lang.reflect.Method;

/**
 * Hub 엔드포인트 동기화 서비스
 * 
 * Hub에서 암복호화 엔드포인트 정보를 조회합니다.
 * AOP에서 사용하며, DADP_CRYPTO_BASE_URL이 없을 때 Hub에서 자동으로 조회합니다.
 * 
 * @author DADP Development Team
 * @version 1.0.0
 * @since 2025-12-19
 */
public class HubEndpointSyncService {
    
    private static final Logger log = LoggerFactory.getLogger(HubEndpointSyncService.class);
    
    private final String hubUrl;
    private final String instanceId;  // AOP 인스턴스 ID (선택적, X-DADP-TENANT 헤더에 사용)
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    public HubEndpointSyncService(String hubUrl, String instanceId) {
        this.hubUrl = hubUrl;
        this.instanceId = instanceId;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    
    /**
     * Hub에서 암복호화 엔드포인트 정보 조회
     * 
     * @return 암복호화 URL, 조회 실패 시 null
     */
    public String getCryptoUrlFromHub() {
        try {
            log.info("🔄 Hub에서 암복호화 엔드포인트 정보 조회 시작: hubUrl={}, instanceId={}", hubUrl, instanceId);
            
            String endpointUrl = hubUrl + "/hub/api/v1/engines/endpoint";
            log.debug("🔗 Hub 엔드포인트 조회 URL: {}", endpointUrl);
            
            // X-DADP-TENANT 헤더 설정 (선택적)
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            if (instanceId != null && !instanceId.trim().isEmpty()) {
                headers.set("X-DADP-TENANT", instanceId);
                log.debug("✅ X-DADP-TENANT 헤더 전송: instanceId={}", instanceId);
            }
            
            org.springframework.http.HttpEntity<?> entity = new org.springframework.http.HttpEntity<>(headers);
            org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(endpointUrl),
                    org.springframework.http.HttpMethod.GET,
                    entity,
                    String.class
            );
            
            // HTTP 상태 코드 조회 (Spring Boot 2.x/3.x 호환)
            int statusCode = getStatusCodeValue(response);
            String responseBody = response.getBody();
            
            if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
                // 응답 파싱
                JsonNode rootNode = objectMapper.readTree(responseBody);
                boolean success = rootNode.path("success").asBoolean(false);
                
                if (!success) {
                    log.warn("⚠️ Hub 엔드포인트 조회 실패: 응답 success=false");
                    return null;
                }
                
                JsonNode dataNode = rootNode.path("data");
                if (dataNode.isMissingNode()) {
                    log.warn("⚠️ Hub 엔드포인트 조회 실패: data 필드 없음");
                    return null;
                }
                
                // cryptoUrl 필드 조회
                String cryptoUrl = dataNode.path("cryptoUrl").asText(null);
                if (cryptoUrl == null || cryptoUrl.trim().isEmpty()) {
                    log.warn("⚠️ Hub 응답에 cryptoUrl이 없음");
                    return null;
                }
                
                log.info("✅ Hub에서 암복호화 엔드포인트 정보 조회 완료: cryptoUrl={}", cryptoUrl);
                return cryptoUrl.trim();
                
            } else {
                log.warn("⚠️ Hub 엔드포인트 조회 실패: HTTP {}", statusCode);
                return null;
            }
            
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (errorMsg.contains("Connection refused") || errorMsg.contains("ConnectException")) {
                log.warn("⚠️ Hub에서 엔드포인트 정보 조회 실패: {} (Hub 연결 불가)", errorMsg);
            } else {
                log.error("❌ Hub에서 엔드포인트 정보 조회 실패: {}", errorMsg, e);
            }
            return null;
        }
    }
    
    /**
     * Spring Boot 2.x/3.x 호환성을 위한 상태 코드 조회
     * 
     * @param response ResponseEntity 객체
     * @return HTTP 상태 코드 값
     */
    private int getStatusCodeValue(org.springframework.http.ResponseEntity<?> response) {
        try {
            // Spring Boot 3.x 방식 시도: getStatusCode().value()
            Method getStatusCodeMethod = response.getClass().getMethod("getStatusCode");
            Object statusCode = getStatusCodeMethod.invoke(response);
            Method valueMethod = statusCode.getClass().getMethod("value");
            return (Integer) valueMethod.invoke(statusCode);
        } catch (Exception e) {
            // Spring Boot 2.x 방식: getStatusCodeValue()
            try {
                Method getStatusCodeValueMethod = response.getClass().getMethod("getStatusCodeValue");
                return (Integer) getStatusCodeValueMethod.invoke(response);
            } catch (Exception e2) {
                log.error("상태 코드 조회 실패", e2);
                return 500; // 기본값
            }
        }
    }
}
