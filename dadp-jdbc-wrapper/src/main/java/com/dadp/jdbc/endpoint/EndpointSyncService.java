package com.dadp.jdbc.endpoint;

import com.dadp.jdbc.config.EndpointStorage;
import com.dadp.common.sync.http.HttpClientAdapter;
import com.dadp.common.sync.http.Java8HttpClientAdapterFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

import java.net.URI;

/**
 * 암복호화 엔드포인트 동기화 서비스
 * 
 * Hub에서 Engine/Gateway URL 정보를 조회하여 영구 저장소에 저장합니다.
 * Hub가 다운되어도 저장된 정보를 사용하여 직접 암복호화 요청을 수행할 수 있습니다.
 * 
 * @deprecated 이 클래스는 더 이상 사용되지 않습니다.
 *             대신 {@link com.dadp.common.sync.endpoint.EndpointSyncService}를 사용하세요.
 * 
 * @author DADP Development Team
 * @version 4.0.0
 * @since 2025-12-05
 */
@Deprecated
public class EndpointSyncService {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(EndpointSyncService.class);
    
    private final String hubUrl;
    private final String hubId;  // Hub가 발급한 고유 ID (X-DADP-TENANT 헤더에 사용)
    private final String alias;  // 사용자가 설정한 instanceId (별칭, 검색/표시용)
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;
    private final EndpointStorage endpointStorage;
    
    public EndpointSyncService(String hubUrl, String hubId, String alias) {
        this.hubUrl = hubUrl;
        this.hubId = hubId;
        this.alias = alias;
        // Java 8용 HTTP 클라이언트 사용 (공통 인터페이스)
        this.httpClient = Java8HttpClientAdapterFactory.create(5000, 10000);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.endpointStorage = new EndpointStorage();
    }
    
    /**
     * Hub에서 엔드포인트 정보 조회 및 저장
     * 
     * @return 동기화 성공 여부
     */
    public boolean syncEndpointsFromHub() {
        try {
            log.info("Fetching crypto endpoint info from Hub: hubUrl={}, hubId={}", hubUrl, hubId);
            
            // V1 API 사용: /hub/api/v1/engines/endpoint
            String endpointPath = "/hub/api/v1/engines/endpoint";
            String endpointUrl = hubUrl + endpointPath;
            log.debug("Hub endpoint query URL: {}", endpointUrl);
            
            URI uri = URI.create(endpointUrl);
            
            // X-DADP-TENANT 헤더에 hubId 전송 (Hub가 인스턴스별 설정을 조회하기 위해 필요)
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            if (hubId != null && !hubId.trim().isEmpty()) {
                headers.put("X-DADP-TENANT", hubId);
                log.debug("X-DADP-TENANT header sent: hubId={}", hubId);
            } else {
                log.warn("hubId not available, X-DADP-TENANT header not sent. Hub cannot retrieve instance-specific config.");
            }
            
            HttpClientAdapter.HttpResponse response = httpClient.get(uri, headers);
            
            int statusCode = response.getStatusCode();
            String responseBody = response.getBody();
            
            if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
                // 응답 파싱
                JsonNode rootNode = objectMapper.readTree(responseBody);
                // v2: code 기반 (primary), v1: success boolean fallback
                boolean success;
                JsonNode codeNode = rootNode.path("code");
                if (!codeNode.isMissingNode() && codeNode.isTextual()) {
                    success = "SUCCESS".equals(codeNode.asText());
                } else {
                    success = rootNode.path("success").asBoolean(false);
                }

                if (!success) {
                    log.warn("Hub endpoint query failed: response success=false");
                    return false;
                }
                
                JsonNode dataNode = rootNode.path("data");
                if (dataNode.isMissingNode()) {
                    log.warn("Hub endpoint query failed: data field missing");
                    return false;
                }
                
                // 통계 설정 조회
                JsonNode statsNode = dataNode.path("statsAggregator");
                Boolean statsAggregatorEnabled = null;
                String statsAggregatorUrl = null;
                String statsAggregatorMode = null;
                Integer slowThresholdMs = null;
                if (!statsNode.isMissingNode()) {
                    statsAggregatorEnabled = statsNode.path("enabled").asBoolean(false);
                    statsAggregatorUrl = statsNode.path("url").asText(null);
                    statsAggregatorMode = statsNode.path("mode").asText(null);
                    JsonNode slowThresholdNode = statsNode.path("slowThresholdMs");
                    if (!slowThresholdNode.isMissingNode() && !slowThresholdNode.isNull()) {
                        slowThresholdMs = slowThresholdNode.asInt(500);
                    }
                }
                
                // 단일 cryptoUrl 필수 (문서 요구사항)
                String cryptoUrl = dataNode.path("cryptoUrl").asText(null);
                if (cryptoUrl == null || cryptoUrl.trim().isEmpty()) {
                    log.warn("Hub response missing cryptoUrl");
                    return false;
                }
                
                // 버전 정보 조회 (Hub 응답에 포함되어 있으면 사용, 없으면 null)
                Long version = null;
                JsonNode versionNode = dataNode.path("version");
                if (!versionNode.isMissingNode()) {
                    version = versionNode.asLong(0);
                }
                
                // 영구 저장소에 저장 (문서 요구사항에 맞게 단순화)
                boolean saved = endpointStorage.saveEndpoints(
                        cryptoUrl.trim(),
                        hubId,  // EndpointSyncService에 이미 있는 hubId 사용
                        version,
                        statsAggregatorEnabled,
                        statsAggregatorUrl,
                        statsAggregatorMode,
                        slowThresholdMs);
                
                if (saved) {
                    log.info("Endpoint info synced from Hub: cryptoUrl={}, hubId={}, version={}, statsEnabled={}",
                            cryptoUrl, hubId, version, statsAggregatorEnabled);
                }
                return saved;
                
            } else {
                log.warn("Hub endpoint query failed: HTTP {}", statusCode);
                // Hub 통신 장애는 알림 제거 (받는 주체가 Hub이므로)
                return false;
            }
            
        } catch (Exception e) {
            // 연결 실패는 예측 가능한 문제이므로 WARN 레벨로 처리 (정책 준수)
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (errorMsg.contains("Connection refused") || errorMsg.contains("ConnectException")) {
                log.warn("Failed to fetch endpoint info from Hub: {} (Hub unreachable)", errorMsg);
            } else {
                // 예측 불가능한 문제만 ERROR로 처리
                log.error("Failed to fetch endpoint info from Hub: {}", errorMsg, e);
            }
            // Hub 통신 장애는 알림 제거 (받는 주체가 Hub이므로)
            return false;
        }
    }
    
    /**
     * 저장된 엔드포인트 정보 로드
     * 
     * @return 엔드포인트 데이터, 로드 실패 시 null
     */
    public EndpointStorage.EndpointData loadStoredEndpoints() {
        return endpointStorage.loadEndpoints();
    }
    
    /**
     * 저장소 경로 조회
     * 
     * @return 저장 경로
     */
    public String getStoragePath() {
        return endpointStorage.getStoragePath();
    }
}

