package com.dadp.aop.service;

import com.dadp.hub.crypto.HubNotificationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * AOP 알림 전송 서비스
 * 
 * AOP에서 발생한 오류를 Hub에 알림으로 전달합니다.
 * `dadp-hub-crypto-lib`의 `HubNotificationClient`를 사용합니다.
 * 
 * @author DADP Development Team
 * @version 1.0.0
 * @since 2025-12-12
 */
@Service
public class AopNotificationService {
    
    private static final Logger log = LoggerFactory.getLogger(AopNotificationService.class);
    
    private final HubNotificationClient notificationClient;
    private final String instanceId;
    
    /**
     * 생성자
     * 
     * @param notificationClient Hub 알림 클라이언트 (nullable)
     * @param environment Spring Environment (spring.application.name 읽기용)
     */
    @Autowired
    public AopNotificationService(@Nullable HubNotificationClient notificationClient,
                                  @Nullable Environment environment) {
        this.notificationClient = notificationClient;
        
        // entityId 결정: 환경 변수 > spring.application.name > 기본값
        String instanceId = System.getenv("DADP_AOP_INSTANCE_ID");
        if (instanceId == null || instanceId.trim().isEmpty()) {
            // Spring의 application.name 읽기
            String appName = null;
            if (environment != null) {
                appName = environment.getProperty("spring.application.name");
            }
            if (appName == null || appName.trim().isEmpty()) {
                appName = System.getProperty("spring.application.name");
            }
            instanceId = (appName != null && !appName.trim().isEmpty()) ? appName : "aop";
        }
        this.instanceId = instanceId.trim();
        
        if (this.notificationClient != null && this.notificationClient.isInitialized()) {
            log.info("✅ AopNotificationService 초기화 완료: instanceId={}", this.instanceId);
        } else {
            log.debug("AopNotificationService 초기화 완료 (알림 클라이언트 없음): instanceId={}", this.instanceId);
        }
    }
    
    /**
     * 알림 전송 가능 여부 확인
     */
    public boolean isAvailable() {
        return notificationClient != null && notificationClient.isInitialized();
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
            "AOP",
            instanceId,
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
        
        notificationClient.sendNotification(type, level, title, message, "AOP", instanceId, metadata);
    }
    
    /**
     * AOP 인스턴스 ID 조회
     */
    public String getInstanceId() {
        return instanceId;
    }
}

