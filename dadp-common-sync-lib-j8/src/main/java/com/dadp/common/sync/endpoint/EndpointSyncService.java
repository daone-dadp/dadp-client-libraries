package com.dadp.common.sync.endpoint;

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
    private final String hubId;  // Hub가 발급한 tenantId (X-DADP-Tenant-Id 헤더에 사용)
    private final String alias;  // 사용자가 설정한 instanceId (별칭, 검색/표시용)
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;
    private final EndpointStorage endpointStorage;
    
    public EndpointSyncService(String hubUrl, String hubId, String alias) {
        this(hubUrl, hubId, alias, new EndpointStorage(requireAlias(alias)), null, null);
    }
    
    public EndpointSyncService(String hubUrl, String hubId, String alias, String storageDir, String fileName) {
        this(hubUrl, hubId, alias, new EndpointStorage(storageDir, fileName), null, null);
    }

    public EndpointSyncService(String hubUrl, String hubId, String alias, String storageDir, String fileName,
                               String ignoredAuthKey, String ignoredAuthSecret) {
        this(hubUrl, hubId, alias, new EndpointStorage(storageDir, fileName), ignoredAuthKey, ignoredAuthSecret);
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
                               String ignoredAuthKey, String ignoredAuthSecret) {
        this.hubUrl = hubUrl;
        this.hubId = hubId;
        this.alias = alias;
        this.httpClient = Java8HttpClientAdapterFactory.create(5000, 10000);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.endpointStorage = endpointStorage;
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
        log.debug("Standalone runtime engine endpoint sync is disabled in DADP 6.0; use refresh response engine.wrapperEngineUrl.");
        return false;
    }

    private java.util.Map<String, String> signedHeaders(String method, URI uri) {
        java.util.Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("X-DADP-Tenant-Id", hubId);
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
