package com.dadp.jdbc.sync;

import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.jdbc.schema.JdbcSchemaSyncService;
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
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Wrapper 정책 매핑 동기화 서비스
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
 * @version 5.2.2
 * @since 2026-01-08
 */
public class JdbcPolicyMappingSyncService {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(JdbcPolicyMappingSyncService.class);
    
    private volatile MappingSyncService mappingSyncService;
    private volatile EndpointSyncService endpointSyncService;
    private final JdbcSchemaSyncService jdbcSchemaSyncService;
    private final PolicyResolver policyResolver;
    private final DirectCryptoAdapter directCryptoAdapter;
    private final EndpointStorage endpointStorage;
    private final ProxyConfig config;
    private final InstanceConfigStorage configStorage;
    private final SchemaStorage schemaStorage;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    
    // HubId 관리자 (core에서 제공)
    private final HubIdManager hubIdManager;
    
    // 정책 매핑 동기화 오케스트레이터 (core에서 제공)
    private final PolicyMappingSyncOrchestrator syncOrchestrator;
    
    // 초기화 상태 플래그
    private volatile boolean initialized = false;
    private final String instanceId;
    private final String datasourceId;
    
    // 주기적 동기화 스케줄러
    private ScheduledExecutorService scheduler;
    
    // 재등록 콜백 (JdbcBootstrapOrchestrator에서 설정, 재등록 시 저장 메타데이터만 사용)
    private Runnable reregistrationCallback;
    
    public JdbcPolicyMappingSyncService(
            MappingSyncService mappingSyncService,
            EndpointSyncService endpointSyncService,
            JdbcSchemaSyncService jdbcSchemaSyncService,
            PolicyResolver policyResolver,
            DirectCryptoAdapter directCryptoAdapter,
            EndpointStorage endpointStorage,
            ProxyConfig config,
            InstanceConfigStorage configStorage,
            SchemaStorage schemaStorage,
            String datasourceId) {
        this.mappingSyncService = mappingSyncService;
        this.endpointSyncService = endpointSyncService;
        this.jdbcSchemaSyncService = jdbcSchemaSyncService;
        this.policyResolver = policyResolver;
        this.directCryptoAdapter = directCryptoAdapter;
        this.endpointStorage = endpointStorage;
        this.config = config;
        this.configStorage = configStorage;
        this.schemaStorage = schemaStorage;
        this.datasourceId = datasourceId;
        
        // InstanceIdProvider 초기화 (core에서 instanceId 관리)
        InstanceIdProvider instanceIdProvider = new InstanceIdProvider(config.getInstanceId());
        this.instanceId = instanceIdProvider.getInstanceId();
        
        // HubIdManager 초기화 (hubId 관리 로직을 core로 위임)
        String hubUrl = config.getHubUrl();
        final JdbcPolicyMappingSyncService self = this;
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
                    // Hub에서 인스턴스 삭제해도 스키마는 datasourceId 기반으로 유지되므로 재전송할 필요 없음
                    // 첫 구동시에만 스키마 전송 (JdbcBootstrapOrchestrator에서 처리)
                    log.info("✅ 재등록 완료: hubId={} (스키마 재전송 생략, Hub에서 datasourceId 기반으로 유지됨)", newHubId);
                }
                
                @Override
                public void onEndpointSynced(Object endpointData) {
                    // 엔드포인트 동기화 후 처리
                    // endpointData는 MappingSyncService.EndpointInfo 또는 null
                    if (endpointData instanceof MappingSyncService.EndpointInfo) {
                        MappingSyncService.EndpointInfo endpointInfo = (MappingSyncService.EndpointInfo) endpointData;
                        // 정책 매핑 응답에서 받은 엔드포인트 정보를 영구저장소에 저장
                        saveEndpointFromPolicyMapping(endpointInfo);
                    } else {
                        // 엔드포인트 정보가 없으면 별도 엔드포인트 조회 (하위 호환성)
                    syncEndpointsAfterPolicyMapping();
                    }
                }
            }
        );
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
        
        log.info("✅ JdbcPolicyMappingSyncService 초기화 완료 알림: initialized={}, hubId={}", initialized, hubId);
        
        // 중요: 스키마 등록이 완료된 후에만 버전 체크 시작
        // hubId가 있고 스키마 등록이 완료된 상태에서만 30초 주기 버전 체크 시작
        if (!initialized || hubId == null || hubId.trim().isEmpty()) {
            log.warn("⚠️ 초기화 조건 미충족: initialized={}, hubId={}", initialized, hubId);
            return;
        }
        
        // 동기화 활성화 (스키마 등록 완료 후에만 활성화)
        setEnabled(true);
        
        // 30초 주기 버전 체크 시작 (스키마 등록 완료 후에만 시작)
        startPeriodicSync();
        
        // 첫 번째 주기적 버전 체크는 30초 후에 실행됨
        // Hub 버전=1, Wrapper 초기 버전=0이므로 첫 버전 체크에서 무조건 갱신 발생
        log.info("✅ 30초 주기 버전 체크 시작: hubId={} (첫 체크는 30초 후 실행)", hubId);
    }
    
    /**
     * 30초 주기로 버전 체크만 수행
     */
    private void startPeriodicSync() {
        if (scheduler != null) {
            return; // 이미 시작됨
        }
        
        log.info("🔄 주기적 정책 매핑 동기화 시작: 30초 주기, instanceId={}, hubId={}, enabled={}, initialized={}", 
                instanceId, hubIdManager.getCachedHubId(), enabled.get(), initialized);
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jdbc-policy-mapping-sync-" + instanceId);
            t.setDaemon(true);
            return t;
        });
        
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!enabled.get() || !initialized) {
                    log.debug("⏭️ 주기적 정책 매핑 동기화 스킵: enabled={}, initialized={}", enabled.get(), initialized);
                    return;
                }
                
                log.trace("🔄 Wrapper 정책 매핑 버전 체크 시작");
                checkMappingChange();
            } catch (Exception e) {
                // 예외가 발생해도 스케줄러는 계속 실행되도록 예외를 잡아서 로그만 출력
                log.warn("⚠️ 주기적 정책 매핑 버전 체크 중 예외 발생 (다음 주기에서 재시도): {}", e.getMessage(), e);
            }
        }, 30, 30, TimeUnit.SECONDS);
        
        log.info("✅ 주기적 정책 매핑 동기화 스케줄러 등록 완료: 30초 주기, instanceId={}", instanceId);
    }
    
    /**
     * EndpointSyncService 재생성 (hubId 업데이트)
     */
    public void updateEndpointSyncService(String hubId, String instanceId) {
        // instanceId를 사용하여 경로 생성 (./dadp/wrapper/instanceId)
        String storageDir = System.getProperty("user.dir") + "/dadp/wrapper/" + instanceId;
        String fileName = "crypto-endpoints.json";
        this.endpointSyncService = new EndpointSyncService(
            config.getHubUrl(), hubId, instanceId, storageDir, fileName);
        log.info("🔄 EndpointSyncService 재생성 완료: hubId={}", hubId);
    }
    
    /**
     * MappingSyncService 재생성 (hubId 업데이트)
     */
    private void updateMappingSyncService(String hubId, String instanceId) {
        String hubUrl = config.getHubUrl();
        String apiBasePath = "/hub/api/v1/proxy";  // V1 API 경로
        this.mappingSyncService = new MappingSyncService(
            hubUrl, hubId, instanceId, datasourceId, apiBasePath, policyResolver);
        log.info("🔄 MappingSyncService 재생성 완료: hubId={}", hubId);
    }
    
    /**
     * 3. 버전 체크만 수행 (30초 주기)
     * - 304 -> 아무것도 안함
     * - 200 -> 갱신 (정책 매핑, url, 버전 등)
     * - 404 -> hub로부터 획득 (플로우a 수행)
     */
    private void checkMappingChange() {
        // core의 오케스트레이터에 위임
        syncOrchestrator.checkMappingChange();
    }
    
    /**
     * 정책 매핑 응답에서 받은 엔드포인트 정보 저장 및 적용
     * 
     * @param endpointInfo 정책 매핑 응답에서 받은 엔드포인트 정보
     */
    private void saveEndpointFromPolicyMapping(MappingSyncService.EndpointInfo endpointInfo) {
        if (endpointInfo == null) {
            log.warn("⚠️ 엔드포인트 정보가 null입니다");
            return;
        }
        
        String cryptoUrl = endpointInfo.getCryptoUrl();
        if (cryptoUrl == null || cryptoUrl.trim().isEmpty()) {
            log.warn("⚠️ 엔드포인트 정보에 cryptoUrl이 없습니다");
            return;
        }
        
        try {
            // hubId와 버전 정보 가져오기
            String currentHubId = hubIdManager.getCachedHubId();
            Long currentVersion = policyResolver.getCurrentVersion();
            
            // EndpointStorage에 저장 (정책 매핑과 함께 받은 버전 사용)
            boolean saved = endpointStorage.saveEndpoints(
                cryptoUrl.trim(),
                currentHubId,
                currentVersion,  // 정책 매핑 버전과 동일한 버전 사용
                false,  // statsAggregatorEnabled (기본값)
                "",     // statsAggregatorUrl (기본값)
                "DIRECT", // statsAggregatorMode (기본값)
                500     // slowThresholdMs (기본값)
            );
            
            if (saved) {
                // 저장된 엔드포인트 정보 로드
                EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
                if (endpointData != null) {
                    // 암복호화 어댑터에 엔드포인트 정보 적용 (캐싱)
                    if (directCryptoAdapter != null) {
                        directCryptoAdapter.setEndpointData(endpointData);
                    }
                    log.info("✅ 정책 매핑 응답에서 엔드포인트 정보 저장 및 적용 완료: cryptoUrl={}, hubId={}, version={}", 
                            cryptoUrl, currentHubId, currentVersion);
                } else {
                    log.warn("⚠️ 엔드포인트 정보 저장 후 로드 실패");
                }
            } else {
                log.warn("⚠️ 엔드포인트 정보 저장 실패");
            }
        } catch (Exception e) {
            log.warn("⚠️ 엔드포인트 정보 저장 실패: {}", e.getMessage());
        }
    }
    
    /**
     * 정책 매핑 동기화 후 엔드포인트 동기화 수행 (하위 호환성, 별도 엔드포인트 조회)
     */
    private void syncEndpointsAfterPolicyMapping() {
        // hubId가 있으면 endpointSyncService를 재생성 (hubId 업데이트)
        String currentHubId = hubIdManager.getCachedHubId();
        if (currentHubId != null && endpointSyncService != null) {
            updateEndpointSyncService(currentHubId, instanceId);
        }
        
        if (endpointSyncService != null) {
            try {
                boolean endpointSynced = endpointSyncService.syncEndpointsFromHub();
                
                if (endpointSynced) {
                    EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
                    if (endpointData != null) {
                        // 암복호화 어댑터에 엔드포인트 정보 적용 (캐싱)
                        if (directCryptoAdapter != null) {
                            directCryptoAdapter.setEndpointData(endpointData);
                        }
                        log.info("✅ 엔드포인트 동기화 완료: cryptoUrl={}, hubId={}, version={}",
                                endpointData.getCryptoUrl(),
                                endpointData.getHubId(),
                                endpointData.getVersion());
                    }
                } else {
                    log.warn("⚠️ 엔드포인트 동기화 실패 (다음 주기에서 재시도)");
                }
            } catch (Exception e) {
                log.warn("⚠️ 엔드포인트 동기화 실패: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 재등록 콜백 설정 (JdbcBootstrapOrchestrator에서 호출)
     */
    public void setReregistrationCallback(Runnable callback) {
        this.reregistrationCallback = callback;
    }
    
    /**
     * Hub에 등록 (404 응답 시 호출됨)
     * 재등록은 Connection 없이 오케스트레이터에 저장된 메타데이터만 사용 (콜백으로 수행).
     */
    private void registerWithHub() {
        try {
            if (reregistrationCallback != null) {
                log.info("📝 Hub 재등록 시작 (저장 메타데이터 사용): instanceId={}", instanceId);
                reregistrationCallback.run();
                String hubId = hubIdManager.hasHubId() ? "hubId 설정됨" : "hubId 없음";
                log.info("✅ Hub 등록 완료: {} (재등록이므로 스키마 재전송 생략)", hubId);
            } else {
                log.warn("⚠️ Hub 재등록 필요하지만 reregistrationCallback이 설정되지 않았습니다.");
            }
        } catch (Exception e) {
            log.error("❌ Hub 재등록 실패: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 동기화 활성화/비활성화
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        if (enabled) {
            log.info("✅ Wrapper 정책 매핑 동기화 활성화");
        } else {
            log.info("⏸️ Wrapper 정책 매핑 동기화 비활성화");
        }
    }
    
    /**
     * 동기화 활성화 여부 확인
     */
    public boolean isEnabled() {
        return enabled.get();
    }
    
    /**
     * 리소스 정리
     */
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
    }
}

