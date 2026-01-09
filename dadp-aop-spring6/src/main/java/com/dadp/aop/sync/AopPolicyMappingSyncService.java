package com.dadp.aop.sync;

import com.dadp.aop.config.DadpAopProperties;
import com.dadp.aop.metadata.EncryptionMetadataInitializer;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.HubIdManager;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.config.InstanceIdProvider;
import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.common.sync.endpoint.EndpointSyncService;
import com.dadp.common.sync.mapping.MappingSyncService;
import com.dadp.common.sync.mapping.PolicyMappingSyncOrchestrator;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.sync.schema.SchemaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AOP 정책 매핑 동기화 서비스
 * 
 * 플로우:
 * 1. 스키마 로드 대기 (스키마가 없으면 아무것도 하지 말고 대기)
 * 2. hubId 획득 (영구저장소에서 1회만 수행. 없으면 hub로부터 획득 - 플로우a 수행)
 * 3. 1과 2번이 완료된 이후부터는 checkMappingChange만 수행
 *    - 304 -> 아무것도 안함
 *    - 200 -> 갱신 (정책 매핑, url, 버전 등)
 *    - 404 -> hub로부터 획득 (플로우a 수행)
 * 
 * @author DADP Development Team
 * @version 5.0.6
 * @since 2025-12-31
 */
@Component
public class AopPolicyMappingSyncService {
    
    private static final Logger log = LoggerFactory.getLogger(AopPolicyMappingSyncService.class);
    
    private volatile MappingSyncService mappingSyncService;
    private volatile EndpointSyncService endpointSyncService;  // hubId 업데이트를 위해 volatile로 변경
    private final AopSchemaSyncServiceV2 aopSchemaSyncService;
    private final PolicyResolver policyResolver;
    private final DirectCryptoAdapter directCryptoAdapter;
    private final EndpointStorage endpointStorage;
    private final DadpAopProperties properties;
    private final Environment environment;
    private final InstanceConfigStorage configStorage;
    private final EncryptionMetadataInitializer metadataInitializer;
    private final SchemaStorage schemaStorage;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    
    // HubId 관리자 (core에서 제공)
    private final HubIdManager hubIdManager;
    
    // 정책 매핑 동기화 오케스트레이터 (core에서 제공)
    private final PolicyMappingSyncOrchestrator syncOrchestrator;
    
    // 초기화 상태 플래그
    private volatile boolean initialized = false;
    private final String instanceId;  // 앱 구동 시 한 번만 설정
    
    public AopPolicyMappingSyncService(MappingSyncService mappingSyncService,
                                      EndpointSyncService endpointSyncService,
                                      AopSchemaSyncServiceV2 aopSchemaSyncService,
                                      PolicyResolver policyResolver,
                                      DirectCryptoAdapter directCryptoAdapter,
                                      EndpointStorage endpointStorage,
                                      DadpAopProperties properties,
                                      Environment environment,
                                      EncryptionMetadataInitializer metadataInitializer) {
        this.mappingSyncService = mappingSyncService;
        this.endpointSyncService = endpointSyncService;
        this.aopSchemaSyncService = aopSchemaSyncService;
        this.policyResolver = policyResolver;
        this.directCryptoAdapter = directCryptoAdapter;
        this.endpointStorage = endpointStorage;
        this.properties = properties;
        this.environment = environment;
        this.metadataInitializer = metadataInitializer;
        
        // InstanceConfigStorage 초기화 (hubId 확인용)
        String storageDir = System.getProperty("user.home") + "/.dadp-aop";
        this.configStorage = new InstanceConfigStorage(storageDir, "aop-config.json");
        
        // SchemaStorage 초기화 (스키마 정책명 업데이트용)
        this.schemaStorage = new SchemaStorage(storageDir, "schemas.json");
        
        // InstanceIdProvider 초기화 (core에서 instanceId 관리)
        // AOP용: Spring property 값 전달
        String springAppName = environment != null ? environment.getProperty("spring.application.name") : null;
        InstanceIdProvider instanceIdProvider = new InstanceIdProvider(springAppName);
        this.instanceId = instanceIdProvider.getInstanceId();
        
        // HubIdManager 초기화 (hubId 관리 로직을 core로 위임)
        String hubUrl = properties.getHubBaseUrl();
        // HubIdManager 콜백에서 syncOrchestrator를 참조할 수 있도록 람다에서 final 변수 사용
        final AopPolicyMappingSyncService self = this;
        this.hubIdManager = new HubIdManager(
            configStorage,
            hubUrl,
            instanceIdProvider,
            (oldHubId, newHubId) -> {
                // hubId 변경 시 MappingSyncService 및 EndpointSyncService 재생성
                if (newHubId != null && !newHubId.trim().isEmpty()) {
                    self.updateMappingSyncService(newHubId, instanceId);
                    self.updateEndpointSyncService(newHubId, instanceId);
                    // syncOrchestrator의 MappingSyncService도 업데이트
                    if (self.syncOrchestrator != null) {
                        self.syncOrchestrator.updateMappingSyncService(self.mappingSyncService);
                    }
                }
            }
        );
        
        // PolicyMappingSyncOrchestrator 초기화 (checkMappingChange 플로우를 core로 위임)
        this.syncOrchestrator = new PolicyMappingSyncOrchestrator(
            hubIdManager,
            mappingSyncService,
            policyResolver,
            schemaStorage,
            new PolicyMappingSyncOrchestrator.SyncCallbacks() {
                @Override
                public void onRegistrationNeeded() {
                    registerWithHub();
                }
                
                @Override
                public void onReregistration(String newHubId) {
                    // 재등록 시에는 스키마 재전송 불필요
                    // Hub에서 인스턴스 삭제해도 스키마는 alias 기반으로 유지되므로 재전송할 필요 없음
                    // 첫 구동시에만 스키마 전송 (AopBootstrapOrchestrator에서 처리)
                    log.info("✅ 재등록 완료: hubId={} (스키마 재전송 생략, Hub에서 alias 기반으로 유지됨)", newHubId);
                }
                
                @Override
                public void onEndpointSynced(Object endpointData) {
                    // 엔드포인트 정보는 정책 스냅샷 응답에서 받아옴
                    if (endpointData instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> endpointInfo = (Map<String, Object>) endpointData;
                        if (endpointInfo != null && !endpointInfo.isEmpty()) {
                            syncEndpointsFromPolicySnapshot(endpointInfo);
                        }
                    }
                }
            }
        );
    }
    
    /**
     * 초기화 (오케스트레이터가 호출)
     * @PostConstruct에서는 허브 관련 초기화를 하지 않음 (오케스트레이터가 담당)
     */
    @PostConstruct
    public void init() {
        // 허브 관련 초기화는 AopBootstrapOrchestrator가 ApplicationReadyEvent 이후에 수행
        // 여기서는 필드 초기화만 수행
        log.debug("📋 AopPolicyMappingSyncService 빈 생성 완료 (초기화는 오케스트레이터가 수행)");
    }
    
    /**
     * 오케스트레이터가 초기화 완료를 알림
     * 
     * @param initialized 초기화 완료 여부
     * @param hubId 캐시된 hubId
     */
    public void setInitialized(boolean initialized, String hubId) {
        this.initialized = initialized;
        
        // HubIdManager를 통해 hubId 설정 (변경 감지 및 콜백 자동 호출)
        if (hubId != null && !hubId.trim().isEmpty()) {
            hubIdManager.setHubId(hubId, false); // 이미 저장되어 있으므로 저장 불필요
        }
        
        log.info("✅ AopPolicyMappingSyncService 초기화 완료 알림: initialized={}, hubId={}", initialized, hubId);
        
        // 중요: 스키마 등록이 완료된 후에만 버전 체크 시작
        // hubId가 있고 스키마 등록이 완료된 상태에서만 30초 주기 버전 체크 시작
        if (!initialized || hubId == null || hubId.trim().isEmpty()) {
            log.warn("⚠️ 초기화 조건 미충족: initialized={}, hubId={}", initialized, hubId);
            return;
        }
        
        // 동기화 활성화 (스키마 등록 완료 후에만 활성화)
        setEnabled(true);
    }
    
    /**
     * 30초 주기로 버전 체크만 수행
     */
    @Scheduled(fixedDelay = 30000) // 30초
    public void checkMappingChangePeriodically() {
        if (!enabled.get() || !initialized) {
            return;
        }
        
        log.trace("🔄 AOP 정책 매핑 버전 체크 시작");
        // core의 오케스트레이터에 위임
        syncOrchestrator.checkMappingChange();
    }
    
    
    /**
     * EndpointSyncService 재생성 (hubId 업데이트)
     */
    public void updateEndpointSyncService(String hubId, String instanceId) {
        String storageDir = System.getProperty("user.home") + "/.dadp-aop";
        String fileName = "crypto-endpoints.json";
        this.endpointSyncService = new EndpointSyncService(
            properties.getHubBaseUrl(), hubId, instanceId, storageDir, fileName);
        log.info("🔄 EndpointSyncService 재생성 완료: hubId={}", hubId);
    }
    
    /**
     * MappingSyncService 재생성 (hubId 업데이트)
     */
    private void updateMappingSyncService(String hubId, String instanceId) {
        String hubUrl = properties.getHubBaseUrl();
        String datasourceId = null; // AOP는 datasourceId 없음
        String apiBasePath = "/hub/api/v1/aop";  // V1 API 경로
        this.mappingSyncService = new MappingSyncService(
            hubUrl, hubId, instanceId, datasourceId, apiBasePath, policyResolver);
        log.info("🔄 MappingSyncService 재생성 완료: hubId={}", hubId);
    }
    
    
    /**
     * 정책 스냅샷에서 받은 엔드포인트 정보를 저장하고 적용
     */
    private void syncEndpointsFromPolicySnapshot(Map<String, Object> endpointInfo) {
        try {
            String cryptoUrl = (String) endpointInfo.get("cryptoUrl");
            if (cryptoUrl == null || cryptoUrl.trim().isEmpty()) {
                log.warn("⚠️ 정책 스냅샷에서 cryptoUrl이 없음");
                return;
            }
            
            String currentHubId = hubIdManager.getCachedHubId();
            if (currentHubId == null || currentHubId.trim().isEmpty()) {
                log.warn("⚠️ hubId가 없어 엔드포인트 정보를 저장할 수 없음");
                return;
            }
            
            // 엔드포인트 정보를 영구 저장소에 저장
            boolean saved = endpointStorage.saveEndpoints(
                cryptoUrl, currentHubId, null,  // version은 정책 스냅샷의 version 사용
                null, null, null, null);  // statsAggregator 정보는 정책 스냅샷에 없을 수 있음
            
            if (saved) {
                // 암복호화 어댑터에 엔드포인트 정보 적용 (캐싱)
                EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
                if (endpointData != null && directCryptoAdapter != null) {
                    directCryptoAdapter.setEndpointData(endpointData);
                }
                log.info("✅ 엔드포인트 동기화 완료: cryptoUrl={}, hubId={}", cryptoUrl, currentHubId);
            } else {
                log.warn("⚠️ 엔드포인트 정보 저장 실패");
            }
        } catch (Exception e) {
            log.warn("⚠️ 엔드포인트 동기화 실패: {}", e.getMessage());
        }
    }
    
    /**
     * Hub에 등록 (인스턴스 등록 → 스키마 동기화)
     * 404 응답 시 호출됨
     */
    private void registerWithHub() {
        String hubUrl = properties.getHubBaseUrl();
        
        // 1단계: 인스턴스 등록 (hubId 발급)
        log.info("📝 1단계: Hub 인스턴스 등록 시작: instanceId={}", instanceId);
        String hubId = registerInstance(hubUrl, instanceId);
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("⚠️ Hub 인스턴스 등록 실패");
            return;
        }
        
        // hubId 저장 (HubIdManager를 통해 저장 및 콜백 자동 호출)
        hubIdManager.setHubId(hubId, true);
        log.info("✅ Hub 인스턴스 등록 완료: hubId={}", hubId);
        
        // 재등록 시에는 스키마 재전송 불필요 (Hub에서 인스턴스 삭제해도 스키마는 유지됨)
        // 첫 구동시에만 스키마 전송 (AopBootstrapOrchestrator에서 처리)
        log.info("✅ Hub 등록 완료: hubId={} (재등록이므로 스키마 재전송 생략)", hubId);
        
        // 엔드포인트 정보는 정책 매핑 스냅샷에서 받아오므로 별도 동기화 불필요
    }
    
    /**
     * Hub에 인스턴스 등록 (hubId 발급)
     * 
     * @param hubUrl Hub URL
     * @param instanceId 인스턴스 ID
     * @return 발급받은 hubId, 실패 시 null
     */
    private String registerInstance(String hubUrl, String instanceId) {
        try {
            // V1 API 사용: /hub/api/v1/aop/instances/register
            String registerUrl = hubUrl + "/hub/api/v1/aop/instances/register";
            
            // 인스턴스 등록 요청 DTO (새 API 형식)
            java.util.Map<String, String> request = new java.util.HashMap<>();
            request.put("instanceId", instanceId);
            request.put("type", "AOP");  // 새 API에 type 필수
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<java.util.Map<String, String>> entity = 
                new org.springframework.http.HttpEntity<>(request, headers);
            
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
     * 동기화 활성화/비활성화
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        if (enabled) {
            log.info("✅ AOP 정책 매핑 동기화 활성화");
        } else {
            log.info("⏸️ AOP 정책 매핑 동기화 비활성화");
        }
    }
    
    /**
     * 동기화 활성화 여부 확인
     */
    public boolean isEnabled() {
        return enabled.get();
    }
    
}

