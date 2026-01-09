package com.dadp.aop.sync;

import com.dadp.aop.metadata.EncryptionMetadataInitializer;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.sync.schema.SchemaCollector;
import com.dadp.common.sync.schema.SchemaMetadata;
import com.dadp.common.sync.schema.SchemaSyncExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * AOP 스키마 동기화 서비스 V2 (Java 17 공통 라이브러리 기반)
 * 
 * RestTemplateSchemaSyncExecutor를 직접 사용하여 스키마 동기화를 제공합니다.
 * 
 * @author DADP Development Team
 * @version 5.2.0
 * @since 2026-01-09
 */
public class AopSchemaSyncServiceV2 {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(AopSchemaSyncServiceV2.class);
    
    private final SchemaSyncExecutor schemaSyncExecutor;
    private final SchemaCollector schemaCollector;
    private final String hubUrl;
    private final String instanceId;
    private final InstanceConfigStorage configStorage;
    private final PolicyResolver policyResolver;
    
    public AopSchemaSyncServiceV2(String hubUrl, 
                                  String instanceId, 
                                  String hubId,
                                  EncryptionMetadataInitializer metadataInitializer,
                                  PolicyResolver policyResolver) {
        this.hubUrl = hubUrl;
        this.instanceId = instanceId;
        this.policyResolver = policyResolver;
        this.configStorage = new InstanceConfigStorage(
            System.getProperty("user.home") + "/.dadp-aop",
            "aop-config.json"
        );
        
        // 스키마 수집기 초기화
        this.schemaCollector = new AopSchemaCollector(metadataInitializer);
        
        // SchemaSyncExecutor 생성 (V1 API 사용: /hub/api/v1/aop)
        RestTemplate restTemplate = new RestTemplate();
        log.info("🔗 AopSchemaSyncServiceV2 초기화: hubUrl={}, apiBasePath=/hub/api/v1/aop", hubUrl);
        this.schemaSyncExecutor = new com.dadp.common.sync.schema.RestTemplateSchemaSyncExecutor(
            hubUrl, "/hub/api/v1/aop", restTemplate);
    }
    
    /**
     * Hub에 스키마 정보 전송
     * 
     * @return 전송 성공 여부
     */
    public boolean syncSchemasToHub() {
        // 영구저장소에서 hubId 로드
        String hubId = loadHubIdFromStorage();
        
        // hubId는 오케스트레이터에서 이미 등록되어 있어야 함
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("⚠️ hubId가 없어 스키마 동기화를 수행할 수 없습니다. 오케스트레이터에서 인스턴스 등록을 먼저 수행해야 합니다.");
            return false;
        }
        
        // 현재 버전 조회
        Long currentVersion = policyResolver != null ? policyResolver.getCurrentVersion() : null;
        
        // 스키마 수집
        List<SchemaMetadata> schemas;
        try {
            schemas = schemaCollector.collectSchemas();
        } catch (Exception e) {
            log.warn("⚠️ 스키마 수집 실패: {}", e.getMessage());
            return false;
        }
        
        if (schemas == null || schemas.isEmpty()) {
            log.debug("📋 전송할 스키마가 없습니다.");
            return true;
        }
        
        // 스키마 동기화 실행
        try {
            boolean synced = schemaSyncExecutor.syncToHub(schemas, hubId, instanceId, currentVersion);
            if (synced) {
                log.info("✅ Hub에 AOP 스키마 정보 전송 완료: {}개 필드, hubId={}", schemas.size(), hubId);
            }
            return synced;
        } catch (Exception e) {
            log.warn("⚠️ Hub 스키마 동기화 실패: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 특정 스키마 목록만 Hub에 전송
     * 
     * @param schemas 전송할 스키마 목록
     * @return 전송 성공 여부
     */
    public boolean syncSpecificSchemasToHub(List<SchemaMetadata> schemas) {
        if (schemas == null || schemas.isEmpty()) {
            log.debug("📋 전송할 스키마가 없습니다.");
            return true;
        }
        
        // 영구저장소에서 hubId 로드
        String hubId = loadHubIdFromStorage();
        
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("⚠️ hubId가 없어 스키마 동기화를 수행할 수 없습니다.");
            return false;
        }
        
        // 현재 버전 조회
        Long currentVersion = policyResolver != null ? policyResolver.getCurrentVersion() : null;
        
        try {
            // SchemaSyncExecutor를 직접 사용하여 특정 스키마만 전송
            log.info("🔗 syncSpecificSchemasToHub 호출: hubUrl={}, hubId={}, 스키마 개수={}", hubUrl, hubId, schemas.size());
            boolean synced = schemaSyncExecutor.syncToHub(schemas, hubId, instanceId, currentVersion);
            
            if (synced) {
                log.info("✅ 특정 스키마 전송 완료: hubId={}, 스키마 개수={}", hubId, schemas.size());
            }
            
            return synced;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.warn("⚠️ 특정 스키마 전송 실패: {} : \"{}\"", e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.warn("⚠️ 특정 스키마 전송 실패: {}", e.getMessage());
            return false;
        }
    }
    
    private String loadHubIdFromStorage() {
        InstanceConfigStorage.ConfigData config = configStorage.loadConfig(hubUrl, instanceId);
        return (config != null && config.getHubId() != null && !config.getHubId().trim().isEmpty()) 
                ? config.getHubId() : null;
    }
}

