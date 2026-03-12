package com.dadp.common.sync.endpoint;

import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

/**
 * 암복호화 엔드포인트 동기화 서비스
 * 
 * Hub에서 Engine/Gateway URL 정보를 조회하여 영구 저장소에 저장합니다.
 * Hub가 다운되어도 저장된 정보를 사용하여 직접 암복호화 요청을 수행할 수 있습니다.
 * Spring RestTemplate을 사용하여 HTTP 통신을 수행합니다.
 * 
 * @author DADP Development Team
 * @version 5.0.4
 * @since 2025-12-30
 */
public class EndpointSyncService {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(EndpointSyncService.class);
    
    private final String hubUrl;
    private final String hubId;  // Hub가 발급한 고유 ID (X-DADP-TENANT 헤더에 사용)
    private final String alias;  // 사용자가 설정한 instanceId (별칭, 검색/표시용)
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final EndpointStorage endpointStorage;
    
    public EndpointSyncService(String hubUrl, String hubId, String alias) {
        this(hubUrl, hubId, alias, EndpointStorage.getInstance());
    }
    
    public EndpointSyncService(String hubUrl, String hubId, String alias,
                               String storageDir, String fileName) {
        this(hubUrl, hubId, alias, new EndpointStorage(storageDir, fileName));
    }
    
    /**
     * EndpointStorage 인스턴스를 직접 받는 생성자 (싱글톤 인스턴스 재사용)
     * 
     * @param hubUrl Hub URL
     * @param hubId Hub가 발급한 고유 ID
     * @param alias 사용자가 설정한 instanceId (별칭)
     * @param endpointStorage EndpointStorage 인스턴스 (싱글톤 재사용)
     */
    public EndpointSyncService(String hubUrl, String hubId, String alias, EndpointStorage endpointStorage) {
        this.hubUrl = hubUrl;
        this.hubId = hubId;
        this.alias = alias;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.endpointStorage = endpointStorage;
    }
    
    /**
     * Hub에서 엔드포인트 정보 조회 및 저장
     * 
     * @return 동기화 성공 여부
     */
    public boolean syncEndpointsFromHub() {
        try {
            log.debug("Fetching crypto endpoint info from Hub: hubUrl={}, hubId={}", hubUrl, hubId);
            
            // V1 API 사용: /hub/api/v1/engines/endpoint
            String endpointPath = "/hub/api/v1/engines/endpoint";
            String endpointUrl = hubUrl + endpointPath;
            log.trace("Hub endpoint query URL: {}", endpointUrl);
            
            // X-DADP-TENANT 헤더에 hubId 전송 (Hub가 인스턴스별 설정을 조회하기 위해 필요)
            HttpHeaders headers = new HttpHeaders();
            if (hubId != null && !hubId.trim().isEmpty()) {
                headers.set("X-DADP-TENANT", hubId);
                log.trace("Sending X-DADP-TENANT header: hubId={}", hubId);
            } else {
                log.warn("hubId is missing, X-DADP-TENANT header will not be sent. Hub cannot query instance-specific settings.");
            }
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            HttpEntity<?> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                endpointUrl, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                // 응답 파싱
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                boolean success = rootNode.path("success").asBoolean(false);
                
                if (!success) {
                    log.warn("Hub endpoint query failed: response success=false");
                    return false;
                }
                
                JsonNode dataNode = rootNode.path("data");
                if (dataNode.isMissingNode()) {
                    log.warn("Hub endpoint query failed: data field is missing");
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
                
                // cryptoUrl 조회
                String cryptoUrl = dataNode.path("cryptoUrl").asText(null);
                if (cryptoUrl == null || cryptoUrl.trim().isEmpty()) {
                    log.warn("Hub endpoint query failed: cryptoUrl is missing");
                    return false;
                }
                
                // version 조회
                Long version = null;
                JsonNode versionNode = dataNode.path("version");
                if (!versionNode.isMissingNode() && !versionNode.isNull()) {
                    version = versionNode.asLong();
                }
                
                // 영구 저장소에 저장
                boolean saved = endpointStorage.saveEndpoints(
                    cryptoUrl, hubId, version,
                    statsAggregatorEnabled, statsAggregatorUrl, statsAggregatorMode,
                    slowThresholdMs);
                
                if (saved) {
                    log.info("Endpoint info synced from Hub: cryptoUrl={}, hubId={}, version={}",
                            cryptoUrl, hubId, version);
                    return true;
                } else {
                    log.warn("Failed to save endpoint info");
                    return false;
                }
            } else {
                log.warn("Hub endpoint query failed: HTTP {}", response.getStatusCode());
                return false;
            }
            
        } catch (Exception e) {
            // 연결 실패는 예측 가능한 문제이므로 WARN 레벨로 처리
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (errorMsg.contains("Connection refused") || errorMsg.contains("ConnectException")) {
                log.warn("Failed to fetch endpoint info from Hub: {} (connection refused)", errorMsg);
            } else {
                // 예측 가능한 문제이므로 WARN 레벨로 처리
                log.warn("Failed to fetch endpoint info from Hub: {}", errorMsg);
            }
            return false;
        }
    }
    
    /**
     * 저장된 엔드포인트 정보 로드
     * 
     * @return 엔드포인트 데이터, 로드 실패 시 null
     */
    public EndpointStorage.EndpointData loadEndpoints() {
        return endpointStorage.loadEndpoints();
    }
    
    /**
     * 저장된 엔드포인트 정보 존재 여부 확인
     * 
     * @return 저장된 정보 존재 여부
     */
    public boolean hasStoredEndpoints() {
        return endpointStorage.hasStoredEndpoints();
    }
}

