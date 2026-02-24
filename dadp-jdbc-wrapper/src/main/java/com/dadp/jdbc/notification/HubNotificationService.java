package com.dadp.jdbc.notification;

import com.dadp.hub.crypto.HubNotificationClient;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

/**
 * Hub 알림 전송 서비스
 * 
 * Wrapper에서 발생한 오류를 Hub에 알림으로 전달합니다.
 * `dadp-hub-crypto-lib`의 `HubNotificationClient`를 사용합니다.
 * 
 * @author DADP Development Team
 * @version 4.0.0
 * @since 2025-12-12
 */
public class HubNotificationService {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(HubNotificationService.class);
    
    private final HubNotificationClient notificationClient;
    private final String hubId;  // Hub가 발급한 고유 ID
    private final String alias;  // 사용자가 설정한 instanceId (별칭)
    
    /**
     * 생성자
     * 
     * @param hubBaseUrl Hub base URL (예: http://hub:9004)
     * @param hubId Hub가 발급한 고유 ID
     * @param alias 사용자가 설정한 instanceId (별칭)
     * @param enableLogging 로그 활성화 (null이면 DADP_ENABLE_LOGGING 환경 변수 확인)
     */
    public HubNotificationService(String hubBaseUrl, String hubId, String alias, Boolean enableLogging) {
        this.hubId = hubId;
        this.alias = alias;
        
        // HubNotificationClient 초기화 (URL이 없거나 초기화 실패 시 null)
        if (hubBaseUrl != null && !hubBaseUrl.trim().isEmpty()) {
            try {
                this.notificationClient = HubNotificationClient.createInstance(hubBaseUrl, 5000, enableLogging);
                log.debug("✅ HubNotificationService 초기화 완료: hubBaseUrl={}, hubId={}, alias={}", 
                        hubBaseUrl, hubId, alias);
            } catch (Exception e) {
                log.warn("⚠️ HubNotificationClient 초기화 실패: {}", e.getMessage());
                // null로 설정하여 안전하게 처리
                throw new RuntimeException("HubNotificationClient 초기화 실패", e);
            }
        } else {
            log.warn("⚠️ Hub URL이 설정되지 않아 알림 기능을 사용할 수 없습니다.");
            this.notificationClient = null;
        }
    }
    
    /**
     * 생성자 (enableLogging 기본값: null - DADP_ENABLE_LOGGING 환경 변수 확인)
     * 
     * @param hubBaseUrl Hub base URL (예: http://hub:9004)
     * @param hubId Hub가 발급한 고유 ID
     * @param alias 사용자가 설정한 instanceId (별칭)
     */
    public HubNotificationService(String hubBaseUrl, String hubId, String alias) {
        this(hubBaseUrl, hubId, alias, null);
    }
    
    /**
     * 알림 전송 가능 여부 확인
     */
    public boolean isAvailable() {
        return notificationClient != null && notificationClient.isInitialized();
    }
    
    /**
     * 엔진 연결 실패 알림 전송 (잘못된 엔드포인트)
     * 
     * Hub 통신 장애가 아닌 엔진 연결 실패만 알림 전송
     * 엔진 자체 문제는 Hub에서 이미 체크하므로 제외
     * 
     * @param engineUrl 엔진 URL
     * @param errorMessage 오류 메시지
     */
    public void notifyEngineConnectionError(String engineUrl, String errorMessage) {
        if (!isAvailable()) {
            log.debug("알림 전송 건너뜀: HubNotificationClient가 초기화되지 않음");
            return;
        }
        
        String message = String.format("엔진 URL: %s, 오류: %s", engineUrl, errorMessage);
        notificationClient.sendNotification(
            "INFRASTRUCTURE_ERROR",
            "WARNING",
            "엔진 연결 실패 (잘못된 엔드포인트)",
            message,
            "PROXY",
            hubId,  // hubId 사용
            null
        );
    }
    
    /**
     * 의도치 않은 예외 알림 전송
     * 
     * 앱 자체에서 처리되지 않은 예상치 못한 Exception 발생 시 전송
     * 
     * @param exception 예외 객체
     * @param context 컨텍스트 정보 (선택사항)
     */
    public void notifyUnexpectedException(Exception exception, String context) {
        if (!isAvailable()) {
            log.debug("알림 전송 건너뜀: HubNotificationClient가 초기화되지 않음");
            return;
        }
        
        String message = String.format("예외 타입: %s, 메시지: %s", 
                exception.getClass().getName(), exception.getMessage());
        if (context != null && !context.trim().isEmpty()) {
            message += ", 컨텍스트: " + context;
        }
        
        notificationClient.sendNotification(
            "SYSTEM_ERROR",
            "ERROR",
            "의도치 않은 예외 발생",
            message,
            "PROXY",
            hubId,  // hubId 사용
            null
        );
    }
    
    /**
     * Hub에 알림 전송 (공통 메서드)
     * 
     * @param type 알림 타입 (CRYPTO_ERROR, SYSTEM_ERROR 등)
     * @param level 알림 레벨 (WARNING, ERROR 등)
     * @param title 알림 제목
     * @param message 알림 메시지
     * @param metadata 메타데이터 (JSON 문자열, 선택)
     */
    public void sendNotification(String type, String level, String title, String message, String metadata) {
        if (!isAvailable()) {
            log.debug("알림 전송 건너뜀: HubNotificationClient가 초기화되지 않음");
            return;
        }
        
        notificationClient.sendNotification(type, level, title, message, "PROXY", hubId, metadata);  // hubId 사용
    }
}

