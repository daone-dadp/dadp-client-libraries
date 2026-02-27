package com.dadp.jdbc.notification;

import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

/**
 * Hub 알림 전송 서비스 (No-op stub)
 *
 * Wrapper에서 발생한 오류를 Hub에 알림으로 전달하는 서비스입니다.
 * 현재는 hub-crypto-lib 의존성을 제거하여 실제 알림 전송 없이 로그만 남깁니다.
 *
 * @author DADP Development Team
 * @version 5.5.5
 * @since 2025-12-12
 */
public class HubNotificationService {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(HubNotificationService.class);

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
        log.debug("HubNotificationService 초기화 (no-op): hubBaseUrl={}, hubId={}, alias={}",
                hubBaseUrl, hubId, alias);
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
     * 알림 전송 가능 여부 확인 (항상 false - no-op)
     */
    public boolean isAvailable() {
        return false;
    }

    /**
     * 엔진 연결 실패 알림 전송 (no-op)
     *
     * @param engineUrl 엔진 URL
     * @param errorMessage 오류 메시지
     */
    public void notifyEngineConnectionError(String engineUrl, String errorMessage) {
        log.debug("알림 전송 건너뜀 (no-op): 엔진 연결 실패 - engineUrl={}, error={}", engineUrl, errorMessage);
    }

    /**
     * 의도치 않은 예외 알림 전송 (no-op)
     *
     * @param exception 예외 객체
     * @param context 컨텍스트 정보 (선택사항)
     */
    public void notifyUnexpectedException(Exception exception, String context) {
        log.debug("알림 전송 건너뜀 (no-op): 예외 발생 - type={}, message={}, context={}",
                exception.getClass().getName(), exception.getMessage(), context);
    }

    /**
     * Hub에 알림 전송 (no-op)
     *
     * @param type 알림 타입 (CRYPTO_ERROR, SYSTEM_ERROR 등)
     * @param level 알림 레벨 (WARNING, ERROR 등)
     * @param title 알림 제목
     * @param message 알림 메시지
     * @param metadata 메타데이터 (JSON 문자열, 선택)
     */
    public void sendNotification(String type, String level, String title, String message, String metadata) {
        log.debug("알림 전송 건너뜀 (no-op): type={}, level={}, title={}", type, level, title);
    }
}
