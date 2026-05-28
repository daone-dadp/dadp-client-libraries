package com.dadp.common.sync.endpoint;

import com.dadp.common.sync.auth.HubInternalAuthSigner;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.http.HttpClientAdapter;
import com.dadp.common.sync.http.Java8HttpClientAdapterFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

import java.net.URI;
import java.util.HashMap;

/**
 * 암복호화 엔드포인트 동기화 서비스
 * 
 * Hub에서 Engine/Gateway URL 정보를 조회하여 영구 저장소에 저장합니다.
 * Hub가 다운되어도 저장된 정보를 사용하여 직접 암복호화 요청을 수행할 수 있습니다.
 * 
 * @author DADP Development Team
 * @version 4.0.0
 * @since 2025-12-05
 */
public class EndpointSyncService {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(EndpointSyncService.class);
    
    private final String hubUrl;
    private final String hubId;  // Hub가 발급한 고유 ID (X-DADP-TENANT 헤더에 사용)
    private final String alias;  // 사용자가 설정한 instanceId (별칭, 검색/표시용)
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;
    private final EndpointStorage endpointStorage;
    private final String runtimeAuthKey;
    private final String runtimeAuthSecret;
    
    public EndpointSyncService(String hubUrl, String hubId, String alias) {
        this(hubUrl, hubId, alias, new EndpointStorage(requireAlias(alias)), null, null);
    }
    
    public EndpointSyncService(String hubUrl, String hubId, String alias, String storageDir, String fileName) {
        this(hubUrl, hubId, alias, new EndpointStorage(storageDir, fileName), null, null);
    }

    public EndpointSyncService(String hubUrl, String hubId, String alias, String storageDir, String fileName,
                               String runtimeAuthKey, String runtimeAuthSecret) {
        this(hubUrl, hubId, alias, new EndpointStorage(storageDir, fileName), runtimeAuthKey, runtimeAuthSecret);
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
        this(hubUrl, hubId, alias, endpointStorage, null, null);
    }

    public EndpointSyncService(String hubUrl, String hubId, String alias, EndpointStorage endpointStorage,
                               String runtimeAuthKey, String runtimeAuthSecret) {
        this.hubUrl = hubUrl;
        this.hubId = hubId;
        this.alias = alias;
        this.httpClient = Java8HttpClientAdapterFactory.create(5000, 10000);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.endpointStorage = endpointStorage;
        this.runtimeAuthKey = runtimeAuthKey;
        this.runtimeAuthSecret = runtimeAuthSecret;
    }

    private static String requireAlias(String alias) {
        if (alias == null || alias.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "DADP wrapper endpoint sync requires alias-scoped storage. Shared/default storage is not allowed.");
        }
        return alias.trim();
    }
    
    /**
     * Hub에서 엔드포인트 정보 조회 및 저장
     * 
     * @return 동기화 성공 여부
     */
    public boolean syncEndpointsFromHub() {
        try {
            log.debug("Querying crypto endpoint info from Hub: hubUrl={}, hubId={}", hubUrl, hubId);
            
            String endpointPath = "/hub/api/v1/runtime/engine-endpoint";
            String endpointUrl = hubUrl + endpointPath;
            log.trace("Hub endpoint query URL: {}", endpointUrl);
            
            URI uri = URI.create(endpointUrl);
            java.util.Map<String, String> headers = signedHeaders("GET", uri);
            
            HttpClientAdapter.HttpResponse response = httpClient.get(uri, headers);
            
            int statusCode = response.getStatusCode();
            String responseBody = response.getBody();
            
            if (statusCode >= 200 && statusCode < 300 && responseBody != null) {
                JsonNode rootNode = objectMapper.readTree(responseBody);
                String cryptoUrl = rootNode.path("publicURL").asText(null);
                if (cryptoUrl == null || cryptoUrl.trim().isEmpty()) {
                    log.warn("Hub runtime engine endpoint response missing publicURL");
                    return false;
                }
                
                // 버전 정보 조회 (Hub 응답에 포함되어 있으면 사용, 없으면 null)
                Long version = null;
                JsonNode versionNode = rootNode.path("metadata").path("version");
                if (!versionNode.isMissingNode()) {
                    String rawVersion = versionNode.asText(null);
                    version = parseVersion(rawVersion);
                }
                
                // 영구 저장소에 저장
                boolean saved = endpointStorage.saveEndpoints(
                        cryptoUrl.trim(),
                        hubId,
                        version,
                        null,
                        null,
                        null,
                        null,
                        null);
                
                if (saved) {
                    log.debug("Endpoint info synced from Hub runtime: cryptoUrl={}, hubId={}, version={}",
                            cryptoUrl, hubId, version);
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
                log.warn("Failed to query endpoint info from Hub: {} (Hub unreachable)", errorMsg);
            } else {
                // 기타 예외도 Hub 통신 장애이므로 WARN 레벨로 처리
                log.warn("Failed to query endpoint info from Hub: {}", errorMsg, e);
            }
            // Hub 통신 장애는 알림 제거 (받는 주체가 Hub이므로)
            return false;
        }
    }

    private java.util.Map<String, String> signedHeaders(String method, URI uri) {
        if (runtimeAuthKey == null || runtimeAuthKey.trim().isEmpty()
                || runtimeAuthSecret == null || runtimeAuthSecret.trim().isEmpty()) {
            throw new IllegalStateException("DADP 6.0 endpoint sync requires internal auth. Configure DADP_WRAPPER_RUNTIME_AUTH_KEY/SECRET or DADP_HUB_INTERNAL_AUTH_KEY/SECRET.");
        }
        java.util.Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        HubInternalAuthSigner signer = new HubInternalAuthSigner(runtimeAuthKey, runtimeAuthSecret);
        headers.putAll(signer.sign(method, uri, new byte[0]));
        return headers;
    }

    private static Long parseVersion(String version) {
        if (version == null || version.trim().isEmpty()) {
            return null;
        }
        String digits = version.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
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
