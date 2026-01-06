package com.dadp.aop.sync;

import com.dadp.aop.config.DadpAopProperties;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.common.sync.endpoint.EndpointSyncService;
import com.dadp.common.sync.mapping.MappingSyncService;
import com.dadp.common.sync.policy.PolicyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AOP 정책 매핑 동기화 서비스
 * 
 * 30초 주기로 Hub에서 정책 매핑 정보와 엔드포인트 정보를 가져와서 저장합니다.
 * 공통 라이브러리의 MappingSyncService와 EndpointSyncService를 사용합니다.
 * EndpointSyncService가 URL을 업데이트하면 DirectCryptoAdapter도 자동으로 업데이트합니다.
 * 
 * Wrapper와 동일한 플로우: hubId가 null이면 스키마 동기화를 먼저 수행하여 hubId를 받습니다.
 * 
 * @author DADP Development Team
 * @version 5.0.6
 * @since 2025-12-31
 */
@Component
public class AopPolicyMappingSyncService {
    
    private static final Logger log = LoggerFactory.getLogger(AopPolicyMappingSyncService.class);
    
    private final MappingSyncService mappingSyncService;
    private volatile EndpointSyncService endpointSyncService;  // hubId 업데이트를 위해 volatile로 변경
    private final AopSchemaSyncService aopSchemaSyncService;
    private final PolicyResolver policyResolver;
    private final DirectCryptoAdapter directCryptoAdapter;
    private final EndpointStorage endpointStorage;
    private final DadpAopProperties properties;
    private final Environment environment;
    private final InstanceConfigStorage configStorage;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    
    public AopPolicyMappingSyncService(MappingSyncService mappingSyncService,
                                      EndpointSyncService endpointSyncService,
                                      AopSchemaSyncService aopSchemaSyncService,
                                      PolicyResolver policyResolver,
                                      DirectCryptoAdapter directCryptoAdapter,
                                      EndpointStorage endpointStorage,
                                      DadpAopProperties properties,
                                      Environment environment) {
        this.mappingSyncService = mappingSyncService;
        this.endpointSyncService = endpointSyncService;
        this.aopSchemaSyncService = aopSchemaSyncService;
        this.policyResolver = policyResolver;
        this.directCryptoAdapter = directCryptoAdapter;
        this.endpointStorage = endpointStorage;
        this.properties = properties;
        this.environment = environment;
        
        // InstanceConfigStorage 초기화 (hubId 확인용)
        String storageDir = System.getProperty("user.home") + "/.dadp-aop";
        this.configStorage = new InstanceConfigStorage(storageDir, "aop-config.json");
    }
    
    /**
     * 초기화 후 즉시 동기화 수행
     */
    @PostConstruct
    public void init() {
        if (enabled.get()) {
            log.info("🔄 AOP 정책 매핑 및 엔드포인트 초기 동기화 시작");
            syncAll();
        }
    }
    
    /**
     * 30초 주기로 정책 매핑 및 엔드포인트 동기화
     */
    @Scheduled(fixedDelay = 30000) // 30초
    public void syncAllPeriodically() {
        if (!enabled.get()) {
            return;
        }
        
        log.trace("🔄 AOP 정책 매핑 및 엔드포인트 주기 동기화 시작");
        syncAll();
    }
    
    /**
     * hubId 확인 및 필요 시 스키마 동기화 수행 (Wrapper와 동일한 플로우)
     * 
     * @return hubId, 없으면 null
     */
    private String ensureHubId() {
        String hubUrl = properties.getHubBaseUrl();
        if (hubUrl == null || hubUrl.trim().isEmpty()) {
            return null;
        }
        
        // AOP 인스턴스 ID 조회
        String instanceId = System.getenv("DADP_AOP_INSTANCE_ID");
        if (instanceId == null || instanceId.trim().isEmpty()) {
            if (environment != null) {
                instanceId = environment.getProperty("spring.application.name", "aop");
            } else {
                instanceId = "aop";
            }
        }
        
        // 저장소에서 hubId 로드 (1회만)
        InstanceConfigStorage.ConfigData config = configStorage.loadConfig(hubUrl, instanceId);
        String hubId = (config != null && config.getHubId() != null && !config.getHubId().trim().isEmpty()) 
                ? config.getHubId() : null;
        
        // hubId가 없으면 스키마 동기화를 먼저 수행 (Wrapper와 동일한 플로우)
        if (hubId == null && aopSchemaSyncService != null) {
            log.info("📝 hubId가 없습니다. 스키마 동기화를 먼저 수행하여 hubId를 받습니다.");
            boolean synced = aopSchemaSyncService.syncSchemasToHub();
            if (synced) {
                // 스키마 동기화 후 저장소에서 hubId 다시 로드
                config = configStorage.loadConfig(hubUrl, instanceId);
                hubId = (config != null && config.getHubId() != null && !config.getHubId().trim().isEmpty()) 
                        ? config.getHubId() : null;
                
                if (hubId != null) {
                    log.info("✅ hubId 수신 완료: hubId={}", hubId);
                    // EndpointSyncService 재생성 (hubId 업데이트)
                    updateEndpointSyncService(hubId, instanceId);
                    
                    // 저장된 엔드포인트 데이터 로드 및 DirectCryptoAdapter 초기화 (Wrapper와 동일)
                    if (endpointSyncService != null && directCryptoAdapter != null) {
                        try {
                            // 1. Hub에서 엔드포인트 정보 조회 시도 (최신 정보 가져오기)
                            boolean syncSuccess = endpointSyncService.syncEndpointsFromHub();
                            
                            // 2. 저장된 엔드포인트 정보 로드 (Hub가 없어도 저장된 정보 사용)
                            EndpointStorage.EndpointData endpointData = endpointSyncService.loadStoredEndpoints();
                            
                            if (endpointData != null && endpointData.getCryptoUrl() != null && !endpointData.getCryptoUrl().trim().isEmpty()) {
                                // 저장된 정보로 DirectCryptoAdapter 업데이트
                                directCryptoAdapter.setEndpointData(endpointData);
                                log.info("✅ DirectCryptoAdapter 초기화 완료: cryptoUrl={}, hubId={}, version={}, syncSuccess={}",
                                        endpointData.getCryptoUrl(),
                                        endpointData.getHubId(),
                                        endpointData.getVersion(),
                                        syncSuccess);
                            } else {
                                log.debug("⏭️ 저장된 엔드포인트 정보가 없습니다. Hub 연결 후 다시 시도하세요.");
                            }
                        } catch (Exception e) {
                            // Hub 동기화 실패해도 저장된 데이터로 동작 가능하도록 시도
                            log.warn("⚠️ 엔드포인트 동기화 실패, 저장된 데이터 로드 시도: {}", e.getMessage());
                            try {
                                EndpointStorage.EndpointData endpointData = endpointSyncService.loadStoredEndpoints();
                                if (endpointData != null && endpointData.getCryptoUrl() != null && !endpointData.getCryptoUrl().trim().isEmpty()) {
                                    directCryptoAdapter.setEndpointData(endpointData);
                                    log.info("✅ 저장된 엔드포인트 정보로 DirectCryptoAdapter 초기화 완료: cryptoUrl={}", endpointData.getCryptoUrl());
                                }
                            } catch (Exception loadEx) {
                                log.warn("⚠️ 저장된 엔드포인트 정보 로드 실패: {}", loadEx.getMessage());
                            }
                        }
                    }
                }
            }
        }
        
        return hubId;
    }
    
    /**
     * EndpointSyncService 재생성 (hubId 업데이트)
     */
    private void updateEndpointSyncService(String hubId, String instanceId) {
        String storageDir = System.getProperty("user.home") + "/.dadp-aop";
        String fileName = "crypto-endpoints.json";
        this.endpointSyncService = new EndpointSyncService(
            properties.getHubBaseUrl(), hubId, instanceId, storageDir, fileName);
        log.info("🔄 EndpointSyncService 재생성 완료: hubId={}", hubId);
    }
    
    /**
     * 정책 매핑 및 엔드포인트 동기화 수행 (Wrapper와 동일한 플로우)
     */
    private void syncAll() {
        // 0. hubId 확인 및 필요 시 스키마 동기화 (Wrapper와 동일한 플로우)
        String hubId = ensureHubId();
        
        // hubId가 없으면 정책 매핑 동기화 불가
        if (hubId == null) {
            log.debug("⏭️ hubId가 없어 정책 매핑 동기화를 건너뜁니다.");
            return;
        }
        
        try {
            // 현재 버전 확인
            Long currentVersion = policyResolver.getCurrentVersion();
            
            // 재등록 감지용 배열 (Wrapper와 동일)
            String[] reregisteredHubId = new String[1];
            
            // Hub에서 변경 여부 확인 (재등록 정보도 함께 확인)
            boolean hasChange = mappingSyncService.checkMappingChange(currentVersion, reregisteredHubId);
            
            // 재등록 감지: Hub 응답에서 재등록 정보 확인 (Wrapper와 동일)
            boolean isReregistered = reregisteredHubId[0] != null;
            if (isReregistered) {
                // 재등록 발생: hubId 업데이트 및 스키마 재전송 (Wrapper와 동일)
                String reregisteredHubIdValue = reregisteredHubId[0];
                log.info("🔄 재등록 발생: hubId={}, 스키마 재전송", reregisteredHubIdValue);
                
                // hubId 업데이트 (저장소에 저장)
                String hubUrl = properties.getHubBaseUrl();
                String instanceId = System.getenv("DADP_AOP_INSTANCE_ID");
                if (instanceId == null || instanceId.trim().isEmpty()) {
                    if (environment != null) {
                        instanceId = environment.getProperty("spring.application.name", "aop");
                    } else {
                        instanceId = "aop";
                    }
                }
                configStorage.saveConfig(reregisteredHubIdValue, hubUrl, instanceId, null);
                
                // EndpointSyncService 재생성 (hubId 업데이트)
                updateEndpointSyncService(reregisteredHubIdValue, instanceId);
                
                // 스키마 재전송 (Hub가 이미 재등록 완료)
                if (aopSchemaSyncService != null) {
                    aopSchemaSyncService.syncSchemasToHub();
                }
            }
            
            if (hasChange) {
                // 버전이 다를 경우 모든 데이터 동기화 (공통 라이브러리 사용)
                log.info("🔄 정책 매핑 변경 감지, Hub에서 최신 정보 로드 시작");
                
                // 1. 정책 매핑 동기화 및 버전 업데이트 (공통 로직)
                int loadedCount = mappingSyncService.syncPolicyMappingsAndUpdateVersion(currentVersion);
                
                // 2. Engine URL 동기화 (엔드포인트 동기화) - 정책 매핑 변경 시에만 수행 (Wrapper와 동일)
                if (endpointSyncService != null) {
                    try {
                        log.trace("🔄 AOP 엔드포인트 동기화 시작");
                        boolean endpointSynced = endpointSyncService.syncEndpointsFromHub();
                        
                        if (endpointSynced) {
                            EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
                            if (endpointData != null) {
                                // 암복호화 어댑터에 엔드포인트 정보 적용
                                if (directCryptoAdapter != null) {
                                    directCryptoAdapter.setEndpointData(endpointData);
                                }
                                // 통계 설정도 함께 동기화됨
                                log.info("🔄 엔드포인트 및 통계 설정 동기화 완료: cryptoUrl={}, hubId={}, version={}, statsEnabled={}, statsUrl={}",
                                        endpointData.getCryptoUrl(),
                                        endpointData.getHubId(),
                                        endpointData.getVersion(),
                                        endpointData.getStatsAggregatorEnabled(),
                                        endpointData.getStatsAggregatorUrl());
                            }
                        } else {
                            log.warn("⚠️ 엔드포인트 동기화 실패 (다음 주기에서 재시도)");
                        }
                    } catch (Exception e) {
                        log.warn("⚠️ 엔드포인트 동기화 실패: {}", e.getMessage());
                    }
                }
            } else {
                log.trace("⏭️ 정책 매핑 변경 없음 (version={})", currentVersion);
            }
            
        } catch (Exception e) {
            log.warn("⚠️ 정책 매핑 동기화 실패: {}", e.getMessage());
        }
    }
    
    /**
     * 동기화 활성화/비활성화
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        if (enabled) {
            log.info("✅ AOP 정책 매핑 동기화 활성화");
            // 활성화 시 즉시 동기화 수행
            syncAll();
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

