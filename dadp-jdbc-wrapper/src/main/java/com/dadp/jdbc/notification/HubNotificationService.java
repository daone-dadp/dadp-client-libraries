package com.dadp.jdbc.notification;

import com.dadp.common.sync.http.HttpClientAdapter;
import com.dadp.common.sync.http.Java8HttpClientAdapterFactory;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hub 알림 전송 서비스
 *
 * Wrapper에서 발생한 오류를 Hub에 알림으로 전달하는 서비스입니다.
 *
 * @author DADP Development Team
 * @version 6.0.0
 * @since 2025-12-12
 */
public class HubNotificationService {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(HubNotificationService.class);
    private static final String NOTIFICATION_PATH = "/hub/api/v1/notifications/external";

    private final String hubBaseUrl;
    private final String tenantId;  // Hub가 발급한 고유 ID
    private final String alias;  // 사용자가 설정한 instanceId (별칭)
    private final Boolean enableLogging;
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 생성자
     *
     * @param hubBaseUrl Hub base URL (예: http://hub:9004)
     * @param tenantId Hub가 발급한 고유 ID
     * @param alias 사용자가 설정한 instanceId (별칭)
     * @param enableLogging 로그 활성화
     */
    public HubNotificationService(String hubBaseUrl, String tenantId, String alias, Boolean enableLogging) {
        this.hubBaseUrl = normalizeBaseUrl(hubBaseUrl);
        this.tenantId = tenantId;
        this.alias = alias;
        this.enableLogging = enableLogging;
        this.httpClient = Java8HttpClientAdapterFactory.create(3000, 5000);
        log.debug("HubNotificationService initialized: hubBaseUrl={}, tenantId={}, alias={}",
                this.hubBaseUrl, tenantId, alias);
    }

    /**
     * 생성자
     *
     * @param hubBaseUrl Hub base URL (예: http://hub:9004)
     * @param tenantId Hub가 발급한 고유 ID
     * @param alias 사용자가 설정한 instanceId (별칭)
     */
    public HubNotificationService(String hubBaseUrl, String tenantId, String alias) {
        this(hubBaseUrl, tenantId, alias, null);
    }

    /**
     * 알림 전송 가능 여부 확인
     */
    public boolean isAvailable() {
        return isNotBlank(hubBaseUrl) && isNotBlank(tenantId);
    }

    /**
     * 엔진 연결 실패 알림 전송 (no-op)
     *
     * @param engineUrl 엔진 URL
     * @param errorMessage 오류 메시지
     */
    public void notifyEngineConnectionError(String engineUrl, String errorMessage) {
        log.debug("Engine connection notification skipped by wrapper policy: engineUrl={}, error={}", engineUrl, errorMessage);
    }

    /**
     * 의도치 않은 예외 알림 전송 (no-op)
     *
     * @param exception 예외 객체
     * @param context 컨텍스트 정보 (선택사항)
     */
    public void notifyUnexpectedException(Exception exception, String context) {
        log.debug("Unexpected exception notification skipped by wrapper policy: type={}, message={}, context={}",
                exception.getClass().getName(), exception.getMessage(), context);
    }

    public void notifyLocalCryptoFailure(String operation,
                                         String policyIdentifier,
                                         String failureType,
                                         String errorMessage,
                                         boolean fallbackRemote,
                                         boolean failOpen) {
        if (!isAvailable()) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("eventCode", "WRAPPER_LOCAL_CRYPTO_FAILURE");
        metadata.put("cryptoMode", "local");
        metadata.put("operation", operation);
        metadata.put("policyIdentifier", policyIdentifier);
        metadata.put("failureType", failureType);
        metadata.put("fallbackRemote", fallbackRemote);
        metadata.put("failOpen", failOpen);
        metadata.put("alias", alias);
        metadata.put("tenantId", tenantId);
        metadata.put("error", truncate(errorMessage, 500));
        sendNotification(
                "CRYPTO_ERROR",
                "ERROR",
                "Wrapper local crypto failure",
                "Wrapper local crypto could not pull or use Hub policy/key material.",
                toJson(metadata));
    }

    /**
     * Hub에 알림 전송
     *
     * @param type 알림 타입 (CRYPTO_ERROR, SYSTEM_ERROR 등)
     * @param level 알림 레벨 (WARNING, ERROR 등)
     * @param title 알림 제목
     * @param message 알림 메시지
     * @param metadata 메타데이터 (JSON 문자열, 선택)
     */
    public void sendNotification(String type, String level, String title, String message, String metadata) {
        if (!isAvailable()) {
            log.debug("Notification skipped: hubBaseUrl or tenantId is missing");
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("type", type);
            payload.put("level", level);
            payload.put("title", title);
            payload.put("message", message);
            payload.put("entityType", "WRAPPER");
            payload.put("entityId", isNotBlank(alias) ? alias : tenantId);
            if (isNotBlank(metadata)) {
                payload.put("metadata", metadata);
            }

            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Accept", "application/json");
            headers.put("X-DADP-Tenant-Id", tenantId);

            HttpClientAdapter.HttpResponse response = httpClient.post(
                    URI.create(hubBaseUrl + NOTIFICATION_PATH),
                    objectMapper.writeValueAsString(payload),
                    headers);
            int status = response != null ? response.getStatusCode() : 0;
            if (status < 200 || status >= 300) {
                log.warn("Hub notification send failed: status={}, type={}, level={}, title={}",
                        status, type, level, title);
                return;
            }
            if (Boolean.TRUE.equals(enableLogging)) {
                log.debug("Hub notification sent: type={}, level={}, title={}", type, level, title);
            }
        } catch (Exception e) {
            log.warn("Hub notification send failed: type={}, level={}, title={}, error={}",
                    type, level, title, e.getMessage());
        }
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String normalizeBaseUrl(String value) {
        if (!isNotBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int apiIndex = trimmed.indexOf("/hub/api/");
        if (apiIndex > 0) {
            trimmed = trimmed.substring(0, apiIndex);
        }
        if (trimmed.endsWith("/hub")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/hub".length());
        }
        return trimmed;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
