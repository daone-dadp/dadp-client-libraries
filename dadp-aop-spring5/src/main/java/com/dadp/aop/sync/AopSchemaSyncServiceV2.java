package com.dadp.aop.sync;

import com.dadp.aop.metadata.EncryptionMetadataInitializer;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.common.sync.config.HubIdSaver;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.sync.schema.RetryableSchemaSyncService;
import com.dadp.common.sync.schema.SchemaCollector;
import com.dadp.common.sync.schema.SchemaMetadata;
import com.dadp.common.sync.schema.SchemaStorage;
import com.dadp.common.sync.schema.SchemaSyncExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * AOP 스키마 동기화 서비스 V2 (공통 라이브러리 기반)
 * 
 * RetryableSchemaSyncService를 사용하여 재시도 로직을 포함한 스키마 동기화를 제공합니다.
 * HubIdSaver를 구현하여 hubId 저장을 처리합니다.
 * 
 * @author DADP Development Team
 * @version 5.2.0
 * @since 2026-01-07
 */
public class AopSchemaSyncServiceV2 {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(AopSchemaSyncServiceV2.class);
    
    private final RetryableSchemaSyncService schemaSyncService;
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
        
        // HubIdSaver 구현 (hubId 저장 콜백)
        HubIdSaver hubIdSaver = new HubIdSaver() {
            @Override
            public void saveHubId(String receivedHubId, String instanceIdParam) {
                configStorage.saveConfig(receivedHubId, hubUrl, instanceIdParam, null);
                log.info("✅ Hub에서 받은 hubId 저장 완료: hubId={}, instanceId={}", receivedHubId, instanceIdParam);
            }
        };
        
        // 스키마 영구저장소 초기화
        String storageDir = System.getProperty("user.home") + "/.dadp-aop";
        SchemaStorage schemaStorage = new SchemaStorage(storageDir, "schemas.json");
        
        // RetryableSchemaSyncService 생성 (공통 로직 사용)
        this.schemaSyncService = new RetryableSchemaSyncService(
            hubUrl,
            new AopSchemaCollector(metadataInitializer),
            createExecutor(hubUrl),
            hubIdSaver,
            schemaStorage,  // 스키마 영구저장소 전달
            // AOP는 이미 수집 완료된 상태이므로 재시도 설정을 다르게 할 수 있음
            1,  // maxRetries: 1회만 시도 (이미 수집 완료)
            0,  // initialDelayMs: 대기 없음
            0   // backoffMs: 대기 없음
        );
    }
    
    private SchemaSyncExecutor createExecutor(String hubUrl) {
        RestTemplate restTemplate = new RestTemplate();
        // V1 API 사용: /hub/api/v1/aop
        return new com.dadp.common.sync.schema.RestTemplateSchemaSyncExecutor(
            hubUrl, "/hub/api/v1/aop", "AOP", restTemplate);
    }
    
    /**
     * Hub에 인스턴스 등록 (hubId 발급)
     * 
     * @return 발급받은 hubId, 실패 시 null
     */
    public String registerInstance() {
        try {
            // V1 API 사용: /hub/api/v1/aop/instances/register (스키마 동기화 시 자동 등록되므로 이 메서드는 사용되지 않을 수 있음)
            // 하지만 하위 호환성을 위해 유지
            String registerUrl = hubUrl + "/hub/api/v1/aop/instances/register";
            
            // 인스턴스 등록 요청 DTO (새 API 형식)
            java.util.Map<String, String> request = new java.util.HashMap<String, String>();
            request.put("instanceId", instanceId);
            request.put("type", "AOP");  // 새 API에 type 필수
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<java.util.Map<String, String>> entity = 
                new org.springframework.http.HttpEntity<java.util.Map<String, String>>(request, headers);
            
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.http.ResponseEntity<java.util.Map> response = restTemplate.exchange(
                registerUrl, 
                org.springframework.http.HttpMethod.POST, 
                entity, 
                java.util.Map.class
            );
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                java.util.Map<String, Object> responseBody = response.getBody();
                Boolean success = (Boolean) responseBody.get("success");
                if (Boolean.TRUE.equals(success)) {
                    Object dataObj = responseBody.get("data");
                    if (dataObj instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> data = (java.util.Map<String, Object>) dataObj;
                        String hubId = (String) data.get("hubId");
                        if (hubId != null && !hubId.trim().isEmpty()) {
                            log.info("✅ Hub 인스턴스 등록 성공: hubId={}, instanceId={}", hubId, instanceId);
                            // hubId 저장
                            configStorage.saveConfig(hubId, hubUrl, instanceId, null);
                            return hubId;
                        }
                    }
                }
            }
            
            log.warn("⚠️ Hub 인스턴스 등록 실패: 응답 형식 오류");
            return null;
        } catch (Exception e) {
            log.warn("⚠️ Hub 인스턴스 등록 실패: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Hub에 스키마 정보 전송 (재시도 로직 포함)
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
        
        // 스키마 동기화 실행 (공통 로직에서 응답의 hubId를 자동으로 저장)
        return schemaSyncService.syncSchemaToHub(hubId, instanceId, currentVersion);
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
            com.dadp.common.sync.schema.SchemaSyncExecutor executor = createExecutor(hubUrl);
            boolean synced = executor.syncToHub(schemas, hubId, instanceId, currentVersion);
            
            if (synced) {
                log.info("✅ 특정 스키마 전송 완료: hubId={}, 스키마 개수={}", hubId, schemas.size());
            }
            
            return synced;
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

