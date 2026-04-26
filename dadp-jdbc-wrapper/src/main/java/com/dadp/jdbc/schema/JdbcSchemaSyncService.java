package com.dadp.jdbc.schema;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.common.sync.config.HubIdManager;
import com.dadp.common.sync.config.HubIdSaver;
import com.dadp.common.sync.schema.RetryableSchemaSyncService;
import com.dadp.common.sync.schema.SchemaCollector;
import com.dadp.common.sync.schema.SchemaMetadata;
import com.dadp.common.sync.schema.SchemaSyncExecutor;
import com.dadp.jdbc.config.ProxyConfig;

import java.sql.Connection;
import java.util.List;

/**
 * JDBC 기반 스키마 동기화 서비스 (Wrapper용, Java 8)
 * 
 * RetryableSchemaSyncService를 사용하여 재시도 로직을 포함한 스키마 동기화를 제공합니다.
 * HubIdSaver를 구현하여 hubId 저장을 처리합니다.
 * 
 * @author DADP Development Team
 * @version 5.1.0
 * @since 2026-01-06
 */
public class JdbcSchemaSyncService {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(JdbcSchemaSyncService.class);
    
    private final RetryableSchemaSyncService schemaSyncService;
    private final String hubUrl;
    private final String apiBasePath;
    private final ProxyConfig proxyConfig;
    private final com.dadp.common.sync.policy.PolicyResolver policyResolver;
    private final HubIdManager hubIdManager; // HubIdManager (null 가능, 있으면 사용)
    
    public JdbcSchemaSyncService(String hubUrl, 
                                SchemaCollector schemaCollector,
                                String apiBasePath,
                                ProxyConfig proxyConfig) {
        this(hubUrl, schemaCollector, apiBasePath, proxyConfig, null, null, 5, 3000, 2000);
    }
    
    public JdbcSchemaSyncService(String hubUrl,
                                SchemaCollector schemaCollector,
                                String apiBasePath,
                                ProxyConfig proxyConfig,
                                int maxRetries,
                                long initialDelayMs,
                                long backoffMs) {
        this(hubUrl, schemaCollector, apiBasePath, proxyConfig, null, null, maxRetries, initialDelayMs, backoffMs);
    }
    
    public JdbcSchemaSyncService(String hubUrl,
                                SchemaCollector schemaCollector,
                                String apiBasePath,
                                ProxyConfig proxyConfig,
                                com.dadp.common.sync.policy.PolicyResolver policyResolver,
                                int maxRetries,
                                long initialDelayMs,
                                long backoffMs) {
        this(hubUrl, schemaCollector, apiBasePath, proxyConfig, policyResolver, null, maxRetries, initialDelayMs, backoffMs);
    }
    
    public JdbcSchemaSyncService(String hubUrl,
                                SchemaCollector schemaCollector,
                                String apiBasePath,
                                ProxyConfig proxyConfig,
                                com.dadp.common.sync.policy.PolicyResolver policyResolver,
                                HubIdManager hubIdManager,
                                int maxRetries,
                                long initialDelayMs,
                                long backoffMs) {
        this.hubUrl = hubUrl;
        this.apiBasePath = apiBasePath;
        this.proxyConfig = proxyConfig;
        this.policyResolver = policyResolver;
        this.hubIdManager = hubIdManager;
        
        // HubIdSaver 구현 (hubId 저장 콜백)
        HubIdSaver hubIdSaver = (receivedHubId, instanceId) -> {
            // HubIdManager가 있으면 HubIdManager 사용 (전역 관리)
            if (hubIdManager != null) {
                hubIdManager.setHubId(receivedHubId, true); // HubIdManager에 저장 (전역 관리)
                log.info("hubId received from Hub saved: hubId={}, instanceId={}", receivedHubId, instanceId);
            } else {
                // HubIdManager가 없으면 에러 (이제는 항상 있어야 함)
                log.error("HubIdManager not available, cannot save hubId: hubId={}", receivedHubId);
            }
        };
        
        // RetryableSchemaSyncService 생성 (공통 로직 사용)
        this.schemaSyncService = new RetryableSchemaSyncService(
            hubUrl,
            schemaCollector,
            createExecutor(hubUrl, apiBasePath),
            hubIdSaver,
            maxRetries,
            initialDelayMs,
            backoffMs
        );
    }
    
    private static SchemaSyncExecutor createExecutor(String hubUrl, String apiBasePath) {
        // 공통 인터페이스 사용 (Java 8용 HTTP 클라이언트)
        com.dadp.common.sync.http.HttpClientAdapter httpClient = com.dadp.common.sync.http.Java8HttpClientAdapterFactory.create(5000, 10000);
        // V1 API (/api/v1/proxy) 사용 시 instanceType 전달
        String instanceType = (apiBasePath != null && apiBasePath.startsWith("/hub/api/v1/proxy")) ? "PROXY" : null;
        return new com.dadp.common.sync.schema.HttpClientSchemaSyncExecutor(hubUrl, apiBasePath, instanceType, httpClient);
    }
    
    /**
     * 스키마 수집 완료까지 대기 (재시도 로직 포함)
     */
    public boolean waitForSchemaCollection(int maxRetries, long retryDelayMs) {
        return schemaSyncService.waitForSchemaCollection(maxRetries, retryDelayMs);
    }
    
    /**
     * 스키마 수집 (재시도) 후 수집 결과 반환.
     * 1단계에서 1회만 수집하고, 3단계 비교 시 이 결과를 재사용하기 위함.
     *
     * @param maxRetries 최대 재시도 횟수
     * @param retryDelayMs 재시도 간격 (밀리초)
     * @return 수집된 스키마 목록 (실패 또는 0개면 null)
     */
    public List<SchemaMetadata> collectSchemasWithRetry(int maxRetries, long retryDelayMs) {
        return schemaSyncService.collectSchemasWithRetry(maxRetries, retryDelayMs);
    }
    
    /**
     * 스키마 수집 (재시도, Connection 전달). instanceId당 1세트 공유 시 부팅 1회에만 호출.
     *
     * @param connection JDBC Connection (null이면 collectSchemas() 사용)
     * @param maxRetries 최대 재시도 횟수
     * @param retryDelayMs 재시도 간격 (밀리초)
     * @return 수집된 스키마 목록 (실패 또는 0개면 null)
     */
    public List<SchemaMetadata> collectSchemasWithRetry(Connection connection, int maxRetries, long retryDelayMs) {
        return schemaSyncService.collectSchemasWithRetry(connection, maxRetries, retryDelayMs);
    }
    
    /**
     * 스키마 메타데이터를 Hub로 동기화 (재시도 로직 포함)
     */
    public boolean syncSchemaToHub(String hubId, String alias, Long currentVersion) {
        return schemaSyncService.syncSchemaToHub(hubId, alias, currentVersion);
    }
    
    /**
     * 스키마 해시 캐시 초기화
     */
    public void clearSchemaHash(String hubId) {
        schemaSyncService.clearSchemaHash(hubId);
    }
    
    /**
     * 특정 스키마 목록만 Hub에 전송 (AOP와 동일한 구조)
     * 
     * @param schemas 전송할 스키마 목록
     * @return 전송 성공 여부
     */
    public boolean syncSpecificSchemasToHub(List<SchemaMetadata> schemas) {
        if (schemas == null || schemas.isEmpty()) {
            log.debug("No schemas to send.");
            return true;
        }
        
        // 영구저장소에서 hubId 로드 (AOP와 동일한 구조)
        String hubId = loadHubIdFromStorage();
        
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("Cannot perform schema sync: hubId not available.");
            return false;
        }
        
        // 현재 버전 조회 (AOP와 동일한 구조)
        Long currentVersion = policyResolver != null ? policyResolver.getCurrentVersion() : null;
        
        try {
            // SchemaSyncExecutor를 직접 사용하여 특정 스키마만 전송 (AOP와 동일한 구조)
            SchemaSyncExecutor executor = createExecutor(hubUrl, apiBasePath);
            boolean synced = executor.syncToHub(schemas, hubId, proxyConfig.getAlias(), currentVersion);
            
            if (synced) {
                log.info("Specific schemas sent: hubId={}, schemaCount={}", hubId, schemas.size());
            }
            
            return synced;
        } catch (Exception e) {
            log.warn("Failed to send specific schemas: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 영구저장소에서 hubId 로드 (AOP와 동일한 구조)
     */
    private String loadHubIdFromStorage() {
        // HubIdManager가 있으면 HubIdManager 사용, 없으면 ProxyConfig 사용 (하위 호환성)
        if (hubIdManager != null) {
            return hubIdManager.getCachedHubId();
        } else {
            return proxyConfig.getHubId();
        }
    }
}
