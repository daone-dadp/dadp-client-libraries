package com.dadp.aop.sync;

import com.dadp.aop.metadata.EncryptionMetadataInitializer;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.sync.schema.RetryableSchemaSyncService;
import com.dadp.common.sync.schema.SchemaCollector;
import com.dadp.common.sync.schema.SchemaSyncExecutor;
import org.springframework.web.client.RestTemplate;

/**
 * AOP 스키마 동기화 서비스 V2 (공통 라이브러리 기반)
 * 
 * RetryableSchemaSyncService를 상속하여 재시도 로직을 포함한 스키마 동기화를 제공합니다.
 * 
 * @author DADP Development Team
 * @version 5.1.0
 * @since 2026-01-06
 */
public class AopSchemaSyncServiceV2 extends RetryableSchemaSyncService {
    
    private final String hubUrl;
    private final String instanceId;
    private final InstanceConfigStorage configStorage;
    private final PolicyResolver policyResolver;
    
    public AopSchemaSyncServiceV2(String hubUrl, 
                                  String instanceId, 
                                  String hubId,
                                  EncryptionMetadataInitializer metadataInitializer,
                                  PolicyResolver policyResolver) {
        super(hubUrl, 
              new AopSchemaCollector(metadataInitializer),
              createExecutor(hubUrl),
              // AOP는 이미 수집 완료된 상태이므로 재시도 설정을 다르게 할 수 있음
              1,  // maxRetries: 1회만 시도 (이미 수집 완료)
              0,  // initialDelayMs: 대기 없음
              0   // backoffMs: 대기 없음
        );
        this.hubUrl = hubUrl;
        this.instanceId = instanceId;
        this.policyResolver = policyResolver;
        this.configStorage = new InstanceConfigStorage(
            System.getProperty("user.home") + "/.dadp-aop",
            "aop-config.json"
        );
    }
    
    private static SchemaSyncExecutor createExecutor(String hubUrl) {
        RestTemplate restTemplate = new RestTemplate();
        return new com.dadp.common.sync.schema.RestTemplateSchemaSyncExecutor(
            hubUrl, "/hub/api/v1/aop", restTemplate);
    }
    
    /**
     * Hub에 스키마 정보 전송 (재시도 로직 포함)
     * 
     * @return 전송 성공 여부
     */
    public boolean syncSchemasToHub() {
        // 영구저장소에서 hubId 로드
        String hubId = loadHubIdFromStorage();
        
        // 현재 버전 조회
        Long currentVersion = policyResolver != null ? policyResolver.getCurrentVersion() : null;
        
        // 스키마 동기화 실행
        boolean success = syncSchemaToHub(hubId, instanceId, currentVersion);
        
        // 성공 시 hubId 저장 (응답에서 받은 경우)
        if (success && hubId != null) {
            // hubId는 이미 저장되어 있으므로 추가 작업 불필요
        }
        
        return success;
    }
    
    private String loadHubIdFromStorage() {
        InstanceConfigStorage.ConfigData config = configStorage.loadConfig(hubUrl, instanceId);
        return (config != null && config.getHubId() != null && !config.getHubId().trim().isEmpty()) 
                ? config.getHubId() : null;
    }
}

