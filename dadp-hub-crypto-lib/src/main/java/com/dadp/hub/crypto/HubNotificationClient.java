package com.dadp.hub.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Hub 알림 전송 클라이언트
 * 
 * Hub에 알림을 전송하는 기능을 제공합니다.
 * `HubCryptoService`와 동일한 방식으로 RestTemplate을 사용합니다.
 * 
 * @author DADP Development Team
 * @version 1.0.0
 * @since 2025-12-12
 */
public class HubNotificationClient {
    
    private static final Logger log = LoggerFactory.getLogger(HubNotificationClient.class);
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private String hubBaseUrl;
    private int timeout;
    private boolean enableLogging;
    private boolean initialized = false;
    
    // Hub 알림 API 경로
    private static final String HUB_NOTIFICATION_PATH = "/hub/api/v1/notifications/external";
    
    /**
     * 생성자
     */
    public HubNotificationClient() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 자동 초기화 메서드 - base URL만 제공
     * 
     * @param hubBaseUrl Hub base URL (예: http://hub:9004)
     * @param timeout 타임아웃 (ms)
     * @param enableLogging 로깅 활성화 (null이면 DADP_ENABLE_LOGGING 환경 변수 확인)
     * @return HubNotificationClient 인스턴스
     */
    public static HubNotificationClient createInstance(String hubBaseUrl, int timeout, Boolean enableLogging) {
        HubNotificationClient instance = new HubNotificationClient();
        
        // base URL 추출 (경로 제거)
        String baseUrl = extractBaseUrl(hubBaseUrl);
        instance.hubBaseUrl = baseUrl;
        instance.timeout = timeout;
        // enableLogging이 null이면 DADP_ENABLE_LOGGING 환경 변수 확인
        boolean logging = enableLogging != null ? enableLogging : isLoggingEnabled();
        instance.enableLogging = logging;
        instance.initialized = true;
        
        if (logging) {
            log.info("✅ HubNotificationClient 자동 초기화 완료: hubBaseUrl={}, timeout={}ms", 
                    baseUrl, timeout);
        }
        
        return instance;
    }
    
    /**
     * DADP_ENABLE_LOGGING 환경 변수 확인
     * 
     * @return 로그 활성화 여부
     */
    private static boolean isLoggingEnabled() {
        String enableLogging = System.getenv("DADP_ENABLE_LOGGING");
        if (enableLogging == null || enableLogging.trim().isEmpty()) {
            enableLogging = System.getProperty("dadp.enable-logging");
        }
        return enableLogging != null && !enableLogging.trim().isEmpty() && 
               ("true".equalsIgnoreCase(enableLogging) || "1".equals(enableLogging));
    }
    
    /**
     * Base URL에서 경로를 제거하여 추출
     * 예: "http://hub:9004/hub" → "http://hub:9004"
     * 
     * @param url 전체 URL 또는 base URL
     * @return base URL (경로 제외)
     */
    private static String extractBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return url;
        }
        
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            
            if (scheme != null && host != null) {
                if (port != -1) {
                    return scheme + "://" + host + ":" + port;
                } else {
                    return scheme + "://" + host;
                }
            }
        } catch (Exception e) {
            // URI 파싱 실패 시 원본 반환
            log.debug("URL 파싱 실패, 원본 사용: {}", url);
        }
        
        return url.trim();
    }
    
    /**
     * 초기화 상태 확인
     */
    public boolean isInitialized() {
        return initialized && hubBaseUrl != null && !hubBaseUrl.trim().isEmpty();
    }
    
    /**
     * Hub에 알림 전송 (공통 메서드)
     * 
     * @param type 알림 타입 (예: CRYPTO_ERROR, SYSTEM_ERROR, INFRASTRUCTURE_ERROR)
     * @param level 알림 레벨 (예: ERROR, WARNING, INFO)
     * @param title 알림 제목
     * @param message 알림 메시지
     * @param entityType 엔티티 타입 (예: ENGINE, PROXY, AOP)
     * @param entityId 엔티티 ID
     * @param metadata 메타데이터 (JSON 문자열, 선택사항)
     * @return 전송 성공 여부
     */
    public boolean sendNotification(String type, String level, String title, String message, 
                                   String entityType, String entityId, String metadata) {
        // 초기화 확인
        if (!isInitialized()) {
            log.warn("⚠️ HubNotificationClient가 초기화되지 않았습니다. 알림 전송을 건너뜁니다.");
            return false;
        }
        
        try {
            // Hub 알림 API URL 구성
            String url = hubBaseUrl + HUB_NOTIFICATION_PATH;
            
            // 요청 본문 구성
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("type", type);
            requestBody.put("level", level);
            requestBody.put("title", title);
            requestBody.put("message", message);
            requestBody.put("entityType", entityType);
            requestBody.put("entityId", entityId);
            if (metadata != null && !metadata.trim().isEmpty()) {
                requestBody.put("metadata", metadata);
            }
            
            String requestBodyJson;
            try {
                requestBodyJson = objectMapper.writeValueAsString(requestBody);
            } catch (Exception e) {
                log.error("알림 요청 데이터 직렬화 실패: {}", e.getMessage());
                return false;
            }
            
            if (enableLogging) {
                log.debug("📢 Hub 알림 전송: type={}, level={}, title={}, entityType={}, entityId={}", 
                        type, level, title, entityType, entityId);
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBodyJson, headers);
            
            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            } catch (org.springframework.web.client.HttpClientErrorException | 
                     org.springframework.web.client.HttpServerErrorException e) {
                log.warn("⚠️ Hub 알림 전송 실패 (HTTP 오류): status={}, message={}", 
                        e.getStatusCode(), e.getMessage());
                return false;
            } catch (Exception e) {
                log.warn("⚠️ Hub 알림 전송 실패 (연결 오류): {}", e.getMessage());
                return false;
            }
            
            if (response.getStatusCode().is2xxSuccessful()) {
                if (enableLogging) {
                    log.debug("✅ Hub 알림 전송 성공: {}", title);
                }
                return true;
            } else {
                log.warn("⚠️ Hub 알림 전송 실패: status={}", response.getStatusCode());
                return false;
            }
            
        } catch (Exception e) {
            log.warn("⚠️ Hub 알림 전송 중 오류 발생: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Hub에 알림 전송 (메타데이터 없음)
     */
    public boolean sendNotification(String type, String level, String title, String message, 
                                   String entityType, String entityId) {
        return sendNotification(type, level, title, message, entityType, entityId, null);
    }
    
    /**
     * Hub Base URL 조회
     */
    public String getHubBaseUrl() {
        return hubBaseUrl;
    }
}

