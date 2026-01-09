package com.dadp.aop.sync;

import com.dadp.aop.config.DadpAopProperties;
import com.dadp.aop.metadata.EncryptionMetadataInitializer;
import com.dadp.aop.sync.AopSchemaCollector;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.common.sync.endpoint.EndpointSyncService;
import com.dadp.common.sync.mapping.MappingSyncService;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.sync.schema.SchemaMetadata;
import com.dadp.common.sync.schema.SchemaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AOP 부팅 플로우 오케스트레이터
 * 
 * ApplicationReadyEvent 이후 단일 진입점에서 전체 부팅 플로우를 수행합니다.
 * 
 * 플로우:
 * 1. 스키마 로드 완료 대기 (게이트)
 * 2. 영구저장소 로드 (hubId, 정책매핑, 버전, URL)
 * 3. Hub 버전 체크 및 동기화
 *    - 304: noop
 *    - 200: update
 *    - 404: register (스키마와 함께)
 * 
 * @author DADP Development Team
 * @version 5.0.6
 * @since 2026-01-07
 */
public class AopBootstrapOrchestrator implements ApplicationListener<ApplicationReadyEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(AopBootstrapOrchestrator.class);
    
    private final EncryptionMetadataInitializer metadataInitializer;
    private final MappingSyncService mappingSyncService;
    private volatile EndpointSyncService endpointSyncService;
    private final AopSchemaSyncServiceV2 aopSchemaSyncService;
    private final PolicyResolver policyResolver;
    private final DirectCryptoAdapter directCryptoAdapter;
    private final EndpointStorage endpointStorage;
    private final DadpAopProperties properties;
    private final Environment environment;
    private final InstanceConfigStorage configStorage;
    private final AopPolicyMappingSyncService policyMappingSyncService;
    private final SchemaStorage schemaStorage;
    
    // 1회 실행 보장
    private final AtomicBoolean started = new AtomicBoolean(false);
    
    // 초기화 완료 플래그
    private volatile boolean initialized = false;
    private volatile String cachedHubId = null;
    
    public AopBootstrapOrchestrator(
            EncryptionMetadataInitializer metadataInitializer,
            @Nullable MappingSyncService mappingSyncService,
            @Nullable EndpointSyncService endpointSyncService,
            @Nullable AopSchemaSyncServiceV2 aopSchemaSyncService,
            PolicyResolver policyResolver,
            DirectCryptoAdapter directCryptoAdapter,
            EndpointStorage endpointStorage,
            DadpAopProperties properties,
            Environment environment,
            AopPolicyMappingSyncService policyMappingSyncService) {
        this.metadataInitializer = metadataInitializer;
        this.mappingSyncService = mappingSyncService;
        this.endpointSyncService = endpointSyncService;
        this.aopSchemaSyncService = aopSchemaSyncService;
        this.policyResolver = policyResolver;
        this.directCryptoAdapter = directCryptoAdapter;
        this.endpointStorage = endpointStorage;
        this.properties = properties;
        this.environment = environment;
        this.policyMappingSyncService = policyMappingSyncService;
        
        // InstanceConfigStorage 초기화
        String storageDir = System.getProperty("user.home") + "/.dadp-aop";
        this.configStorage = new InstanceConfigStorage(storageDir, "aop-config.json");
        
        // SchemaStorage 초기화
        this.schemaStorage = new SchemaStorage(storageDir, "schemas.json");
    }
    
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // 1회 실행 보장
        if (!started.compareAndSet(false, true)) {
            log.debug("⏭️ AopBootstrapOrchestrator는 이미 실행되었습니다.");
            return;
        }
        
        // Hub URL이 없으면 실행하지 않음
        String hubUrl = properties.getHubBaseUrl();
        if (hubUrl == null || hubUrl.trim().isEmpty()) {
            log.debug("⏭️ Hub URL이 설정되지 않아 부팅 플로우를 건너뜁니다.");
            return;
        }
        
        log.info("🚀 AOP 부팅 플로우 오케스트레이터 시작");
        
        // 별도 스레드에서 실행 (애플리케이션 시작을 블로킹하지 않음)
        CompletableFuture.runAsync(() -> {
            try {
                runBootstrapFlow();
            } catch (Exception e) {
                log.error("❌ AOP 부팅 플로우 실패: {}", e.getMessage(), e);
            }
        }, java.util.concurrent.ForkJoinPool.commonPool());
    }
    
    /**
     * 부팅 플로우 실행
     */
    private void runBootstrapFlow() {
        try {
            // 1. 스키마 로드 완료 대기 (게이트)
            log.info("⏳ 1단계: 스키마 로드 완료 대기");
            CompletableFuture<Void> schemaLoaded = metadataInitializer.awaitLoaded();
            schemaLoaded.get(30, TimeUnit.SECONDS); // 최대 30초 대기
            log.info("✅ 스키마 로드 완료");
            
            // 1-1. 스키마를 영구저장소에 저장 (정책명 없이)
            saveSchemasToStorage();
            
            // 2. 영구저장소 로드 (hubId, 정책매핑, 버전, URL)
            log.info("📂 2단계: 영구저장소에서 데이터 로드");
            String hubId = loadFromPersistentStorage();
            
            // 3. Hub 버전 체크 및 동기화는 policyMappingSyncService의 checkMappingChange()로 통일
            // 부팅 시에는 hubId만 설정하고, 실제 버전 체크는 policyMappingSyncService가 주기적으로 수행
            log.info("🔄 3단계: Hub 버전 체크 및 동기화 (policyMappingSyncService에 위임)");
            if (hubId == null) {
                // hubId가 없으면 스키마와 함께 등록
                registerWithHub();
            } else {
                // hubId가 있어도 스키마가 Hub에 없을 수 있으므로 확인 및 재전송
                ensureSchemasSyncedToHub(hubId);
            }
            
            initialized = true;
            log.info("✅ AOP 부팅 플로우 완료: hubId={}, initialized={}", cachedHubId, initialized);
            
            // AopPolicyMappingSyncService에 초기화 완료 알림
            if (policyMappingSyncService != null) {
                policyMappingSyncService.setInitialized(true, cachedHubId);
            }
            
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("⚠️ 스키마 로드 대기 시간 초과 (30초), 계속 진행합니다.");
            // 타임아웃이어도 계속 진행
            try {
                String hubId = loadFromPersistentStorage();
                if (hubId != null) {
                    checkAndSyncWithHub(hubId);
                } else {
                    registerWithHub();
                }
                initialized = true;
                if (policyMappingSyncService != null) {
                    policyMappingSyncService.setInitialized(true, cachedHubId);
                }
            } catch (Exception ex) {
                log.error("❌ 부팅 플로우 실패: {}", ex.getMessage(), ex);
            }
        } catch (Exception e) {
            log.error("❌ 부팅 플로우 실패: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 영구저장소에서 데이터 로드 (hubId, 정책매핑, 버전, URL)
     * 
     * @return hubId, 없으면 null
     */
    private String loadFromPersistentStorage() {
        String hubUrl = properties.getHubBaseUrl();
        if (hubUrl == null || hubUrl.trim().isEmpty()) {
            return null;
        }
        
        String instanceId = getInstanceId();
        
        // hubId 로드
        InstanceConfigStorage.ConfigData config = configStorage.loadConfig(hubUrl, instanceId);
        String hubId = (config != null && config.getHubId() != null && !config.getHubId().trim().isEmpty()) 
                ? config.getHubId() : null;
        
        // 정책매핑과 버전은 PolicyResolver 생성자에서 이미 로드됨
        // URL은 EndpointStorage에서 로드
        // 스키마는 SchemaStorage에서 로드 (변경 여부 확인용)
        
        // 스키마 로드 및 변경 여부 확인
        if (hubId != null) {
            try {
                List<SchemaMetadata> storedSchemas = schemaStorage.loadSchemas();
                if (!storedSchemas.isEmpty()) {
                    // 현재 스키마 수집 (비교용)
                    AopSchemaCollector schemaCollector = new AopSchemaCollector(metadataInitializer);
                    List<SchemaMetadata> currentSchemas = schemaCollector.collectSchemas();
                    
                    // 스키마 해시 비교
                    String storedHash = calculateSchemaHash(storedSchemas);
                    String currentHash = calculateSchemaHash(currentSchemas);
                    
                    if (!storedHash.equals(currentHash)) {
                        log.info("📋 스키마 변경 감지: 저장된 스키마와 현재 스키마가 다릅니다. (저장: {}개, 현재: {}개)", 
                                storedSchemas.size(), currentSchemas != null ? currentSchemas.size() : 0);
                    } else {
                        log.info("✅ 스키마 변경 없음: 저장된 스키마와 동일합니다. ({}개)", storedSchemas.size());
                    }
                } else {
                    log.debug("📋 영구저장소에 스키마 없음 (첫 실행 또는 Hub에서 동기화 예정)");
                }
            } catch (Exception e) {
                log.warn("⚠️ 스키마 로드 실패: {}", e.getMessage());
            }
        }
        
        // 엔드포인트 정보 로드 및 DirectCryptoAdapter 초기화
        if (hubId != null && directCryptoAdapter != null) {
            try {
                EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
                if (endpointData != null && endpointData.getCryptoUrl() != null && !endpointData.getCryptoUrl().trim().isEmpty()) {
                    directCryptoAdapter.setEndpointData(endpointData);
                    log.info("✅ DirectCryptoAdapter 초기화 완료: cryptoUrl={}, hubId={}, version={}",
                            endpointData.getCryptoUrl(),
                            endpointData.getHubId(),
                            endpointData.getVersion());
                }
            } catch (Exception e) {
                log.warn("⚠️ 엔드포인트 정보 로드 실패: {}", e.getMessage());
            }
        }
        
        if (hubId != null) {
            cachedHubId = hubId;
            log.info("✅ 영구저장소에서 hubId 로드 완료: hubId={}", hubId);
        } else {
            log.info("📋 영구저장소에 hubId 없음 → Hub 등록 시도");
        }
        
        return hubId;
    }
    
    /**
     * 스키마를 영구저장소에 저장 및 상태 비교
     */
    private void saveSchemasToStorage() {
        try {
            // 현재 스키마 수집 (정책명 없이)
            AopSchemaCollector schemaCollector = new AopSchemaCollector(metadataInitializer);
            List<SchemaMetadata> currentSchemas = schemaCollector.collectSchemas();
            
            if (currentSchemas == null || currentSchemas.isEmpty()) {
                log.debug("📋 수집된 스키마가 없어 저장하지 않습니다.");
                return;
            }
            
            // 정책명을 null로 설정
            for (SchemaMetadata schema : currentSchemas) {
                if (schema != null) {
                    schema.setPolicyName(null); // 정책명은 null로 저장
                }
            }
            
            // 영구저장소와 비교하여 상태 업데이트
            int updatedCount = schemaStorage.compareAndUpdateSchemas(currentSchemas);
            log.info("💾 스키마 영구저장소에 저장 및 상태 업데이트 완료: {}개 스키마 업데이트", updatedCount);
            
        } catch (Exception e) {
            log.warn("⚠️ 스키마 저장 실패: {}", e.getMessage());
        }
    }
    
    /**
     * 저장된 스키마의 정책명 업데이트
     * SchemaStorage의 공통 메서드 사용
     */
    private void updateSchemaPolicyNames() {
        try {
            // PolicyResolver에서 정책 매핑 가져오기 (공통 로직 사용)
            // Java 17 버전에서는 getAllMappings() 메서드 사용 가능 (core 라이브러리에 있음)
            Map<String, String> policyMappings = policyResolver.getAllMappings();
            if (policyMappings == null || policyMappings.isEmpty()) {
                log.debug("📋 업데이트할 정책 매핑이 없음");
                return;
            }
            
            // SchemaStorage의 공통 메서드 사용
            int updatedCount = schemaStorage.updatePolicyNames(policyMappings);
            if (updatedCount > 0) {
                log.info("✅ 스키마 정책명 업데이트 완료: {}개 스키마", updatedCount);
            }
        } catch (Exception e) {
            log.warn("⚠️ 스키마 정책명 업데이트 실패: {}", e.getMessage());
        }
    }
    
    /**
     * 스키마 해시 계산 (변경 감지용)
     * 
     * @param schemas 스키마 메타데이터 목록
     * @return 해시 값 (SHA-256)
     */
    private String calculateSchemaHash(List<SchemaMetadata> schemas) {
        try {
            if (schemas == null || schemas.isEmpty()) {
                return "";
            }
            
            // 스키마를 문자열로 직렬화
            StringBuilder sb = new StringBuilder();
            for (SchemaMetadata schema : schemas) {
                if (schema == null) {
                    continue; // null 스키마는 건너뜀
                }
                sb.append(schema.getDatabaseName() != null ? schema.getDatabaseName() : "").append("|");
                sb.append(schema.getSchemaName() != null ? schema.getSchemaName() : "").append("|");
                sb.append(schema.getTableName() != null ? schema.getTableName() : "").append("|");
                sb.append(schema.getColumnName() != null ? schema.getColumnName() : "").append("|");
                sb.append(schema.getColumnType() != null ? schema.getColumnType() : "").append("|");
                sb.append(schema.getIsNullable() != null ? schema.getIsNullable() : "").append("|");
                sb.append(schema.getColumnDefault() != null ? schema.getColumnDefault() : "").append("|");
                sb.append(schema.getPolicyName() != null ? schema.getPolicyName() : "").append("\n");
            }
            
            // SHA-256 해시 계산
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(sb.toString().getBytes("UTF-8"));
            
            // 16진수 문자열로 변환
            StringBuilder hashString = new StringBuilder();
            for (byte b : hashBytes) {
                hashString.append(String.format("%02x", b));
            }
            
            return hashString.toString();
        } catch (Exception e) {
            log.warn("⚠️ 스키마 해시 계산 실패, 기본값 사용: {}", e.getMessage());
            // 해시 계산 실패 시 타임스탬프 사용 (항상 변경된 것으로 간주)
            return String.valueOf(System.currentTimeMillis());
        }
    }
    
    /**
     * Hub 버전 체크 및 동기화
     * 
     * @param hubId Hub ID
     */
    private void checkAndSyncWithHub(String hubId) {
        if (mappingSyncService == null) {
            log.warn("⚠️ MappingSyncService가 없어 Hub 버전 체크를 건너뜁니다.");
            return;
        }
        
        try {
            // 현재 버전 확인
            Long currentVersion = policyResolver.getCurrentVersion();
            log.debug("📋 Hub 버전 확인 요청: 현재 버전={}", currentVersion);
            
            // 재등록 감지용 배열
            String[] reregisteredHubId = new String[1];
            
            // Hub에서 버전 변경 여부 확인
            boolean hasChange = mappingSyncService.checkMappingChange(currentVersion, reregisteredHubId);
            
            // 재등록 처리
            boolean isReregistered = reregisteredHubId[0] != null;
            if (isReregistered) {
                String reregisteredHubIdValue = reregisteredHubId[0];
                log.info("🔄 재등록 발생: hubId={}, 스키마 재전송", reregisteredHubIdValue);
                
                // hubId 업데이트
                String hubUrl = properties.getHubBaseUrl();
                String instanceId = getInstanceId();
                configStorage.saveConfig(reregisteredHubIdValue, hubUrl, instanceId, null);
                cachedHubId = reregisteredHubIdValue;
                
                // EndpointSyncService 재생성
                updateEndpointSyncService(reregisteredHubIdValue, instanceId);
                
                // 스키마 재전송
                if (aopSchemaSyncService != null) {
                    aopSchemaSyncService.syncSchemasToHub();
                }
                
                // 재등록 후에는 정책 매핑을 강제로 동기화
                hasChange = true;
                currentVersion = 0L;
            }
            
            // 버전 체크 결과에 따라 처리
            if (hasChange) {
                // 200 OK: 버전 변경 -> 갱신
                log.info("🔄 정책 매핑 변경 감지, Hub에서 최신 정보 로드 시작");
                
                // 정책 매핑 동기화 및 버전 업데이트
                // 정책 매핑의 키가 스키마 정보(table.column)이고, 값이 null이면 스키마는 있지만 정책이 없는 상태
                int loadedCount = mappingSyncService.syncPolicyMappingsAndUpdateVersion(currentVersion);
                
                // 저장된 스키마의 정책명 업데이트
                updateSchemaPolicyNames();
                
                // 엔드포인트 동기화
                if (endpointSyncService != null) {
                    try {
                        boolean endpointSynced = endpointSyncService.syncEndpointsFromHub();
                        
                        if (endpointSynced) {
                            EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
                            if (endpointData != null) {
                                if (directCryptoAdapter != null) {
                                    directCryptoAdapter.setEndpointData(endpointData);
                                }
                                log.info("✅ 정책 매핑 및 엔드포인트 동기화 완료: 매핑={}개, cryptoUrl={}, hubId={}, version={}",
                                        loadedCount,
                                        endpointData.getCryptoUrl(),
                                        endpointData.getHubId(),
                                        endpointData.getVersion());
                            }
                        }
                    } catch (Exception e) {
                        log.warn("⚠️ 엔드포인트 동기화 실패: {}", e.getMessage());
                    }
                }
            } else {
                // 304 Not Modified: 버전 동일 -> 아무것도 하지 않음
                log.trace("⏭️ 정책 매핑 변경 없음 (version={}, 304 Not Modified)", currentVersion);
            }
            
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 404 Not Found: hubId를 찾을 수 없음 -> 등록
            if (e.getStatusCode() == org.springframework.http.HttpStatus.NOT_FOUND) {
                log.info("🔄 Hub에서 hubId를 찾을 수 없음 (404), 등록 수행");
                registerWithHub();
            } else {
                log.warn("⚠️ Hub 버전 체크 실패: HTTP {}, message={}", e.getStatusCode(), e.getMessage());
            }
        } catch (Exception e) {
            log.warn("⚠️ Hub 버전 체크 실패: {}", e.getMessage());
        }
    }
    
    /**
     * Hub에 등록 (인스턴스 등록 → 스키마 동기화)
     */
    private void registerWithHub() {
        String hubUrl = properties.getHubBaseUrl();
        String instanceId = getInstanceId();
        
        // 1단계: 인스턴스 등록 (hubId 발급)
        log.info("📝 1단계: Hub 인스턴스 등록 시작: instanceId={}", instanceId);
        String hubId = registerInstance(hubUrl, instanceId);
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("⚠️ Hub 인스턴스 등록 실패");
            return;
        }
        
        // hubId 저장
        configStorage.saveConfig(hubId, hubUrl, instanceId, null);
        cachedHubId = hubId;
        log.info("✅ Hub 인스턴스 등록 완료: hubId={}", hubId);
        
        // EndpointSyncService 재생성
        updateEndpointSyncService(hubId, instanceId);
        
        // 2단계: 생성 상태 스키마 전송 (hubId 획득 후)
        if (aopSchemaSyncService == null) {
            log.warn("⚠️ AopSchemaSyncService가 없어 스키마 동기화를 수행할 수 없습니다.");
            return;
        }
        
        // 생성 상태의 스키마 조회
        List<SchemaMetadata> createdSchemas = schemaStorage.getCreatedSchemas();
        if (!createdSchemas.isEmpty()) {
            log.info("📝 2단계: 생성 상태 스키마 Hub 전송 시작: hubId={}, 스키마 개수={}", hubId, createdSchemas.size());
            
            // 생성 상태 스키마만 전송 (Hub의 /schemas/sync 엔드포인트 호출)
            boolean synced = syncCreatedSchemasToHub(hubId, createdSchemas);
            if (synced) {
                // Hub의 /schemas/sync 엔드포인트 응답을 받았으므로 REGISTERED로 변경
                List<String> schemaKeys = new java.util.ArrayList<>();
                for (SchemaMetadata schema : createdSchemas) {
                    if (schema != null) {
                        schemaKeys.add(schema.getKey());
                    }
                }
                int updatedCount = schemaStorage.updateSchemasStatus(schemaKeys, SchemaMetadata.Status.REGISTERED);
                log.info("✅ 생성 상태 스키마 전송 완료 및 상태 업데이트: {}개 스키마 (CREATED -> REGISTERED)", updatedCount);
            } else {
                log.warn("⚠️ 생성 상태 스키마 전송 실패 (Hub 응답 없음)");
            }
        } else {
            log.info("📝 2단계: 생성 상태 스키마 없음, 전체 스키마 동기화 수행: hubId={}", hubId);
            // 생성 상태 스키마가 없으면 전체 스키마 동기화 (Hub의 /schemas/sync 엔드포인트 호출)
            boolean synced = aopSchemaSyncService.syncSchemasToHub();
            if (!synced) {
                log.warn("⚠️ Hub 스키마 동기화 실패 (Hub 응답 없음)");
                return;
            }
            
            // Hub의 /schemas/sync 엔드포인트 응답을 받았으므로 모든 스키마를 REGISTERED로 변경
            List<SchemaMetadata> allSchemas = schemaStorage.loadSchemas();
            List<String> allSchemaKeys = new java.util.ArrayList<>();
            for (SchemaMetadata schema : allSchemas) {
                if (schema != null && !SchemaMetadata.Status.REGISTERED.equals(schema.getStatus())) {
                    allSchemaKeys.add(schema.getKey());
                }
            }
            if (!allSchemaKeys.isEmpty()) {
                int updatedCount = schemaStorage.updateSchemasStatus(allSchemaKeys, SchemaMetadata.Status.REGISTERED);
                log.info("✅ 전체 스키마 전송 완료 및 상태 업데이트: {}개 스키마 (REGISTERED)", updatedCount);
            }
        }
        
        log.info("✅ Hub 등록 완료: hubId={}", hubId);
        
        // 엔드포인트 정보는 정책 매핑 동기화 시 함께 받아옴 (별도 조회 불필요)
    }
    
    /**
     * Hub에 스키마가 동기화되어 있는지 확인하고 필요시 재전송
     * 
     * @param hubId Hub ID
     */
    private void ensureSchemasSyncedToHub(String hubId) {
        if (aopSchemaSyncService == null) {
            log.warn("⚠️ AopSchemaSyncService가 없어 스키마 동기화를 수행할 수 없습니다.");
            return;
        }
        
        try {
            // 영구저장소에서 스키마 로드
            List<SchemaMetadata> storedSchemas = schemaStorage.loadSchemas();
            
            if (storedSchemas == null || storedSchemas.isEmpty()) {
                log.info("📋 영구저장소에 스키마 없음, 스키마 수집 및 전송");
                // 스키마 수집 및 전송
                AopSchemaCollector schemaCollector = new AopSchemaCollector(metadataInitializer);
                List<SchemaMetadata> currentSchemas = schemaCollector.collectSchemas();
                if (!currentSchemas.isEmpty()) {
                    // 정책명을 null로 설정
                    for (SchemaMetadata schema : currentSchemas) {
                        if (schema != null) {
                            schema.setPolicyName(null);
                        }
                    }
                    // 상태 비교 및 업데이트
                    schemaStorage.compareAndUpdateSchemas(currentSchemas);
                    // 생성 상태 스키마 전송
                    List<SchemaMetadata> createdSchemas = schemaStorage.getCreatedSchemas();
                    if (!createdSchemas.isEmpty()) {
                        // Hub 등록 엔드포인트 응답을 받은 후에만 REGISTERED로 변경
                        boolean synced = syncCreatedSchemasToHub(hubId, createdSchemas);
                        if (synced) {
                            // Hub의 /schemas/sync 엔드포인트 응답을 받았으므로 REGISTERED로 변경
                            schemaStorage.updateSchemasStatus(
                                createdSchemas.stream().map(SchemaMetadata::getKey).collect(java.util.stream.Collectors.toList()),
                                SchemaMetadata.Status.REGISTERED);
                        }
                    } else {
                        // 생성 상태가 없으면 전체 전송
                        boolean synced = aopSchemaSyncService.syncSchemasToHub();
                        if (synced) {
                            // Hub의 /schemas/sync 엔드포인트 응답을 받았으므로 모든 스키마를 REGISTERED로 변경
                            List<SchemaMetadata> allSchemas = schemaStorage.loadSchemas();
                            List<String> allSchemaKeys = new java.util.ArrayList<>();
                            for (SchemaMetadata schema : allSchemas) {
                                if (schema != null && !SchemaMetadata.Status.REGISTERED.equals(schema.getStatus())) {
                                    allSchemaKeys.add(schema.getKey());
                                }
                            }
                            if (!allSchemaKeys.isEmpty()) {
                                schemaStorage.updateSchemasStatus(allSchemaKeys, SchemaMetadata.Status.REGISTERED);
                            }
                        }
                    }
                }
            } else {
                // 저장된 스키마가 있으면 생성 상태 스키마 전송
                List<SchemaMetadata> createdSchemas = schemaStorage.getCreatedSchemas();
                if (!createdSchemas.isEmpty()) {
                    log.info("📝 생성 상태 스키마 Hub 전송: hubId={}, 스키마 개수={}", hubId, createdSchemas.size());
                    boolean synced = syncCreatedSchemasToHub(hubId, createdSchemas);
                    if (synced) {
                        List<String> schemaKeys = new java.util.ArrayList<>();
                        for (SchemaMetadata schema : createdSchemas) {
                            if (schema != null) {
                                schemaKeys.add(schema.getKey());
                            }
                        }
                        schemaStorage.updateSchemasStatus(schemaKeys, SchemaMetadata.Status.REGISTERED);
                        log.info("✅ 생성 상태 스키마 전송 완료 및 상태 업데이트: {}개 스키마", createdSchemas.size());
                    }
                } else {
                    // 생성 상태 스키마가 없으면 REGISTERED 상태 스키마도 전송 (Hub 재배포 등으로 인해 없을 수 있음)
                    List<SchemaMetadata> registeredSchemas = storedSchemas.stream()
                        .filter(s -> s != null && SchemaMetadata.Status.REGISTERED.equals(s.getStatus()))
                        .collect(java.util.stream.Collectors.toList());
                    
                    if (!registeredSchemas.isEmpty()) {
                        log.info("📝 REGISTERED 상태 스키마 Hub 재전송 (Hub 재배포 등으로 인해 없을 수 있음): hubId={}, 스키마 개수={}", 
                                hubId, registeredSchemas.size());
                        // Hub 등록 엔드포인트 응답을 받은 후에만 REGISTERED 유지
                        boolean synced = syncCreatedSchemasToHub(hubId, registeredSchemas);
                        if (synced) {
                            // Hub의 /schemas/sync 엔드포인트 응답을 받았으므로 REGISTERED 상태 유지
                            log.info("✅ REGISTERED 상태 스키마 재전송 완료: {}개 스키마", registeredSchemas.size());
                        } else {
                            // 전송 실패 시 CREATED로 변경하여 재시도 가능하도록
                            List<String> schemaKeys = registeredSchemas.stream()
                                .map(SchemaMetadata::getKey)
                                .collect(java.util.stream.Collectors.toList());
                            schemaStorage.updateSchemasStatus(schemaKeys, SchemaMetadata.Status.CREATED);
                            log.warn("⚠️ REGISTERED 상태 스키마 재전송 실패, CREATED로 변경: {}개 스키마", registeredSchemas.size());
                        }
                    } else {
                        log.debug("📋 전송할 스키마 없음, Hub에 이미 동기화된 것으로 간주");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ 스키마 동기화 확인 실패: {}", e.getMessage());
        }
    }
    
    /**
     * 생성 상태 스키마만 Hub에 전송
     * 
     * @param hubId Hub ID
     * @param createdSchemas 생성 상태 스키마 목록
     * @return 전송 성공 여부
     */
    private boolean syncCreatedSchemasToHub(String hubId, List<SchemaMetadata> createdSchemas) {
        if (aopSchemaSyncService == null || createdSchemas == null || createdSchemas.isEmpty()) {
            return false;
        }
        
        try {
            // 특정 스키마만 전송
            return aopSchemaSyncService.syncSpecificSchemasToHub(createdSchemas);
        } catch (Exception e) {
            log.warn("⚠️ 생성 상태 스키마 전송 실패: {}", e.getMessage());
            return false;
        }
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
     * EndpointSyncService 재생성 (hubId 업데이트)
     */
    private void updateEndpointSyncService(String hubId, String instanceId) {
        String storageDir = System.getProperty("user.home") + "/.dadp-aop";
        String fileName = "crypto-endpoints.json";
        this.endpointSyncService = new EndpointSyncService(
            properties.getHubBaseUrl(), hubId, instanceId, storageDir, fileName);
        log.info("🔄 EndpointSyncService 재생성 완료: hubId={}", hubId);
        
        // AopPolicyMappingSyncService의 endpointSyncService도 업데이트
        if (policyMappingSyncService != null) {
            policyMappingSyncService.updateEndpointSyncService(hubId, instanceId);
        }
    }
    
    /**
     * 인스턴스 ID 조회
     */
    private String getInstanceId() {
        String instanceId = System.getenv("DADP_AOP_INSTANCE_ID");
        if (instanceId == null || instanceId.trim().isEmpty()) {
            if (environment != null) {
                instanceId = environment.getProperty("spring.application.name", "aop");
            } else {
                instanceId = "aop";
            }
        }
        return instanceId;
    }
    
    /**
     * 초기화 완료 여부 확인
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 캐시된 hubId 조회
     */
    public String getCachedHubId() {
        return cachedHubId;
    }
}

