package com.dadp.jdbc.sync;

import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.HubIdManager;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.config.InstanceIdProvider;
import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.common.sync.endpoint.EndpointSyncService;
import com.dadp.common.sync.mapping.MappingSyncService;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.sync.schema.SchemaMetadata;
import com.dadp.common.sync.schema.SchemaStorage;
import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;
import com.dadp.jdbc.mapping.DatasourceRegistrationService;
import com.dadp.jdbc.schema.JdbcSchemaCollector;
import com.dadp.jdbc.schema.JdbcSchemaSyncService;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JDBC Wrapper 부팅 플로우 오케스트레이터
 * 
 * AOP의 AopBootstrapOrchestrator와 동일한 패턴을 따릅니다.
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
 * @version 5.2.2
 * @since 2026-01-08
 */
public class JdbcBootstrapOrchestrator {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(JdbcBootstrapOrchestrator.class);
    
    // instanceId별 1회 실행 보장 (static으로 전역 관리)
    private static final ConcurrentHashMap<String, AtomicBoolean> instanceStartedMap = new ConcurrentHashMap<>();
    
    // 1회 실행 보장 (인스턴스별)
    private final AtomicBoolean started = new AtomicBoolean(false);
    
    // 공통 라이브러리 사용
    private final PolicyResolver policyResolver;
    private MappingSyncService mappingSyncService; // hubId 획득 후 초기화
    private EndpointSyncService endpointSyncService; // hubId 획득 후 초기화
    private final EndpointStorage endpointStorage;
    private final InstanceConfigStorage configStorage;
    private final SchemaStorage schemaStorage;
    private DirectCryptoAdapter directCryptoAdapter;
    private final HubIdManager hubIdManager; // 전역 hubId 관리
    
    // Wrapper 전용
    private JdbcSchemaSyncService schemaSyncService;
    private JdbcSchemaCollector schemaCollector;
    private final ProxyConfig config;
    private final Connection connection;
    private final String originalUrl;
    
    // 정책 매핑 동기화 서비스 (AOP와 동일한 구조)
    private JdbcPolicyMappingSyncService policyMappingSyncService;
    
    // 초기화 완료 플래그
    private volatile boolean initialized = false;
    private volatile String cachedDatasourceId = null;
    // hubId는 HubIdManager에서 전역으로 관리 (cachedHubId 필드 제거)
    
    public JdbcBootstrapOrchestrator(
            Connection connection,
            String originalUrl,
            ProxyConfig config) {
        this.connection = connection;
        this.originalUrl = originalUrl;
        this.config = config;
        
        // InstanceConfigStorage 초기화
        String storageDir = System.getProperty("user.home") + "/.dadp-wrapper";
        this.configStorage = new InstanceConfigStorage(storageDir, "proxy-config.json");
        
        // SchemaStorage 초기화
        this.schemaStorage = new SchemaStorage(storageDir, "schemas.json");
        
        // HubIdManager 초기화 (전역 hubId 관리)
        java.util.Map<String, String> urlParams = config.getUrlParams();
        InstanceIdProvider instanceIdProvider = new InstanceIdProvider(urlParams);
        this.hubIdManager = new HubIdManager(
            configStorage,
            config.getHubUrl(),
            instanceIdProvider,
            (oldHubId, newHubId) -> {
                // hubId 변경 시 MappingSyncService 재생성
                if (newHubId != null && !newHubId.equals(oldHubId)) {
                    log.info("🔄 hubId 변경 감지: {} -> {}, MappingSyncService 재생성", oldHubId, newHubId);
                    initializeServicesWithHubId(newHubId);
                }
            }
        );
        
        // PolicyResolver 초기화 (싱글톤)
        this.policyResolver = PolicyResolver.getInstance();
        
        // EndpointStorage 초기화 (싱글톤)
        this.endpointStorage = EndpointStorage.getInstance();
        
        // 스키마 수집기 초기화 (datasourceId는 나중에 설정, ProxyConfig 전달)
        this.schemaCollector = new JdbcSchemaCollector(connection, null, config);
        
        // 스키마 동기화 서비스 초기화 (V1 API 사용: /hub/api/v1/proxy)
        // HubIdManager 전달하여 전역 hubId 관리
        this.schemaSyncService = new JdbcSchemaSyncService(
            config.getHubUrl(),
            schemaCollector,
            "/hub/api/v1/proxy",  // V1 API 경로
            config,
            policyResolver,  // AOP와 동일하게 policyResolver 전달
            hubIdManager,    // HubIdManager 전달 (전역 hubId 관리)
            5,      // maxRetries
            3000,   // initialDelayMs
            2000    // backoffMs
        );
        
        // MappingSyncService와 EndpointSyncService는 hubId가 필요하므로 나중에 초기화
        // initializeServicesWithHubId()에서 초기화됨
    }
    
    /**
     * 부팅 플로우 실행
     * 
     * @return 초기화 완료 여부
     */
    public boolean runBootstrapFlow() {
        // instanceId 기반으로 전역 1회 실행 보장
        String instanceId = config.getInstanceId();
        AtomicBoolean instanceStarted = instanceStartedMap.computeIfAbsent(instanceId, k -> new AtomicBoolean(false));
        
        if (!instanceStarted.compareAndSet(false, true)) {
            log.trace("⏭️ JdbcBootstrapOrchestrator는 이미 실행되었습니다 (instanceId={})", instanceId);
            // 이미 실행된 경우, HubIdManager에서 hubId를 로드하여 초기화 상태 확인
            String loadedHubId = hubIdManager.loadFromStorage();
            if (loadedHubId != null && !loadedHubId.trim().isEmpty()) {
                // hubId가 있으면 초기화 완료된 것으로 간주
                this.initialized = true;
                // 서비스 초기화 (hubId가 있는 경우)
                initializeServicesWithHubId(loadedHubId);
                // datasourceId도 로드 시도
                try {
                    DatabaseMetaData metaData = connection.getMetaData();
                    String dbVendor = metaData.getDatabaseProductName().toLowerCase();
                    String host = extractHostFromUrl(originalUrl);
                    int port = extractPortFromUrl(originalUrl);
                    String database = connection.getCatalog();
                    String schema = extractSchemaName(connection, dbVendor);
                    
                    String cachedDatasourceId = com.dadp.jdbc.config.DatasourceStorage.loadDatasourceId(
                        dbVendor, host, port, database, schema);
                    if (cachedDatasourceId != null && !cachedDatasourceId.trim().isEmpty()) {
                        this.cachedDatasourceId = cachedDatasourceId;
                    }
                } catch (Exception e) {
                    log.debug("datasourceId 로드 실패 (무시): {}", e.getMessage());
                }
                return true;
            }
            // hubId가 없으면 초기화 실패로 간주
            return false;
        }
        
        // 인스턴스별 실행 플래그도 설정
        if (!started.compareAndSet(false, true)) {
            log.trace("⏭️ 이 인스턴스는 이미 실행되었습니다.");
            return initialized;
        }
        
        try {
            // Hub URL이 없으면 실행하지 않음
            String hubUrl = config.getHubUrl();
            if (hubUrl == null || hubUrl.trim().isEmpty()) {
                log.debug("⏭️ Hub URL이 설정되지 않아 부팅 플로우를 건너뜁니다.");
                return false;
            }
            
            log.info("🚀 JDBC Wrapper 부팅 플로우 오케스트레이터 시작");
            
            // 1. 스키마 로드 완료 대기 (게이트)
            log.info("⏳ 1단계: 스키마 로드 완료 대기");
            boolean schemaLoaded = schemaSyncService.waitForSchemaCollection(5, 2000);
            if (!schemaLoaded) {
                log.warn("⚠️ 스키마 로드가 완료되지 않았지만 계속 진행합니다 (fail-open 모드)");
            }
            log.info("✅ 스키마 로드 완료");
            
            // 1-1. 스키마를 영구저장소에 저장 (정책명 없이)
            saveSchemasToStorage();
            
            // 2. 영구저장소 로드 (hubId는 HubIdManager에서 관리, 다른 데이터도 로드)
            log.info("📂 2단계: 영구저장소에서 데이터 로드");
            String hubId = hubIdManager.loadFromStorage(); // HubIdManager에서 전역으로 관리
            loadOtherDataFromPersistentStorage(); // 다른 데이터 로드
            
            // 3. Hub 등록 및 스키마 등록 (hubId가 없으면 등록, 있으면 스키마만 동기화)
            log.info("🔄 3단계: Hub 등록 및 스키마 등록");
            boolean schemaRegistrationCompleted = false;
            
            if (hubId == null) {
                // hubId가 없으면 Datasource 등록 및 스키마 등록
                schemaRegistrationCompleted = registerWithHub();
                // registerWithHub()에서 hubId를 설정하므로 HubIdManager에서 다시 로드
                hubId = hubIdManager.getCachedHubId();
            } else {
                // hubId가 있으면 생성 상태 스키마만 Hub에 등록
                // 재등록이 발생할 수 있으므로 HubIdManager에서 최신 hubId 확인
                String oldHubId = hubId;
                schemaRegistrationCompleted = ensureSchemasSyncedToHub(hubId);
                // 재등록이 발생했다면 HubIdManager에서 최신 hubId 가져오기
                String newHubId = hubIdManager.getCachedHubId();
                if (newHubId != null && !newHubId.equals(oldHubId)) {
                    log.info("🔄 재등록으로 인한 hubId 변경: {} -> {}", oldHubId, newHubId);
                    hubId = newHubId;
                }
            }
            
            // hubId가 없거나 스키마 등록이 완료되지 않으면 다음 단계 진행 불가
            if (hubId == null || hubId.trim().isEmpty()) {
                log.warn("⚠️ hubId가 없어 정책 매핑 동기화를 시작할 수 없습니다.");
                initialized = false;
                return false;
            }
            
            if (!schemaRegistrationCompleted) {
                log.warn("⚠️ 스키마 등록이 완료되지 않아 정책 매핑 동기화를 시작할 수 없습니다.");
                initialized = false;
                return false;
            }
            
            // 4. 서비스 초기화 (hubId가 확보되고 스키마 등록이 완료된 후에만)
            log.info("🔄 4단계: 서비스 초기화");
            initializeServicesWithHubId(hubId);
                
                // HubIdManager에 hubId 설정 (전역 관리)
            hubIdManager.setHubId(hubId, true);
                
            // 5. 정책 매핑 동기화 서비스 초기화 및 위임 (AOP와 동일한 구조)
            initializePolicyMappingSyncService(hubId);
            
            // 6. 스키마 등록 완료 후 정책 매핑 동기화 서비스 활성화 (30초 주기 버전 체크 시작)
            // 중요: 스키마 등록이 완료된 후에만 버전 체크 시작 (hubId가 있고 스키마 등록이 완료된 상태)
            initialized = true;
            if (policyMappingSyncService != null) {
                policyMappingSyncService.setInitialized(true, hubId);
            }
            log.info("✅ JDBC Wrapper 부팅 플로우 완료: hubId={}, datasourceId={}", hubIdManager.getCachedHubId(), cachedDatasourceId);
            return true;
            
        } catch (Exception e) {
            log.error("❌ 부팅 플로우 실패: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 영구저장소에서 데이터 로드 (hubId는 HubIdManager에서 관리하므로 제거)
     */
    private void loadOtherDataFromPersistentStorage() {
        // PolicyResolver는 싱글톤이므로 이미 로드됨
        Long loadedPolicyVersion = policyResolver.getCurrentVersion();
        if (loadedPolicyVersion != null) {
            log.info("📂 영구저장소에서 정책 매핑 로드 완료: version={}", loadedPolicyVersion);
        }
        
        // EndpointStorage는 싱글톤이므로 이미 로드됨
        EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
        if (endpointData != null) {
            log.info("📂 영구저장소에서 엔드포인트 정보 로드 완료: cryptoUrl={}, hubId={}, version={}", 
                    endpointData.getCryptoUrl(), endpointData.getHubId(), endpointData.getVersion());
        }
        
        // SchemaStorage에서 스키마 로드
        List<SchemaMetadata> storedSchemas = schemaStorage.loadSchemas();
        if (!storedSchemas.isEmpty()) {
            log.info("📂 영구저장소에서 스키마 로드 완료: {}개", storedSchemas.size());
        }
        
        // DatasourceStorage에서 datasourceId 로드
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String dbVendor = metaData.getDatabaseProductName().toLowerCase();
            String host = extractHostFromUrl(originalUrl);
            int port = extractPortFromUrl(originalUrl);
            String database = connection.getCatalog();
            String schema = extractSchemaName(connection, dbVendor);
            
            String cachedDatasourceId = com.dadp.jdbc.config.DatasourceStorage.loadDatasourceId(
                dbVendor, host, port, database, schema);
            if (cachedDatasourceId != null && !cachedDatasourceId.trim().isEmpty()) {
                this.cachedDatasourceId = cachedDatasourceId;
                log.info("✅ 저장된 datasourceId 로드: datasourceId={}", this.cachedDatasourceId);
            }
        } catch (Exception e) {
            log.warn("⚠️ datasourceId 로드 실패: {}", e.getMessage());
        }
    }
    
    /**
     * Hub에 등록 (V1 API: Datasource 등록에서 hubId와 datasourceId를 동시에 받음)
     * 
     * @return 스키마 등록 완료 여부 (hubId 등록 및 스키마 등록 성공 시 true)
     */
    private boolean registerWithHub() {
        String hubUrl = config.getHubUrl();
        String instanceId = config.getInstanceId();
        
        // V1 API: Datasource 등록에서 hubId와 datasourceId를 동시에 받음
        log.info("📝 Hub Datasource 등록 시작: instanceId={}", instanceId);
        DatasourceRegistrationService.DatasourceInfo datasourceInfo = registerDatasource();
        if (datasourceInfo == null) {
            log.warn("⚠️ Datasource 등록 실패");
            return false;
        }
        
        // hubId와 datasourceId 저장
        String hubId = datasourceInfo.getHubId();
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("⚠️ Datasource 등록 응답에 hubId가 없습니다");
            return false;
        }
        
        // HubIdManager에 hubId 설정 (전역 관리, 영구저장소에 자동 저장)
        hubIdManager.setHubId(hubId, true);
        log.info("✅ Hub Datasource 등록 완료: hubId={}, datasourceId={}", hubId, datasourceInfo.getDatasourceId());
        
        // EndpointSyncService 초기화
        this.endpointSyncService = new EndpointSyncService(
            config.getHubUrl(),
            hubId,
            instanceId,
            endpointStorage
        );
        
        // datasourceId가 설정된 후 schemaCollector와 schemaSyncService 재생성 (Wrapper는 datasourceId 필수)
        if (cachedDatasourceId != null && !cachedDatasourceId.trim().isEmpty()) {
            this.schemaCollector = new JdbcSchemaCollector(connection, cachedDatasourceId, config);
            this.schemaSyncService = new JdbcSchemaSyncService(
                config.getHubUrl(),
                schemaCollector,
                "/hub/api/v1/proxy",  // V1 API 경로
                config,
                policyResolver,
                hubIdManager,    // HubIdManager 전달 (전역 hubId 관리)
                5,      // maxRetries
                3000,   // initialDelayMs
                2000    // backoffMs
            );
            log.debug("✅ datasourceId 설정 후 schemaCollector 재생성: datasourceId={}", cachedDatasourceId);
        }
        
        // 3단계: 생성 상태 스키마 전송 (AOP와 동일한 구조)
        if (schemaSyncService == null) {
            log.warn("⚠️ JdbcSchemaSyncService가 없어 스키마 동기화를 수행할 수 없습니다.");
            return false;
        }
        
        List<SchemaMetadata> createdSchemas = schemaStorage.getCreatedSchemas();
        if (!createdSchemas.isEmpty()) {
            log.info("📝 3단계: 생성 상태 스키마 Hub 전송 시작: hubId={}, 스키마 개수={}", hubId, createdSchemas.size());
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
                log.info("✅ Hub 등록 완료: hubId={}", hubId);
                return true;  // 스키마 등록 성공
            } else {
                log.warn("⚠️ 생성 상태 스키마 전송 실패 (Hub 응답 없음)");
                return false;  // 스키마 등록 실패
            }
        } else {
            log.info("📝 3단계: 생성 상태 스키마 없음 (이미 등록된 스키마만 존재)");
        log.info("✅ Hub 등록 완료: hubId={}", hubId);
            return true;  // 등록할 스키마가 없으면 완료로 간주
        }
        
        // 엔드포인트 동기화는 버전 체크 후 정책 매핑과 함께 받아오므로 여기서는 제거
        // PolicyMappingSyncOrchestrator의 콜백에서 엔드포인트 정보를 받아서 저장함
    }
    
    /**
     * Hub에 인스턴스 등록 (hubId 발급) - AOP와 동일
     * 
     * @param hubUrl Hub URL
     * @param instanceId 인스턴스 ID
     * @return 발급받은 hubId, 실패 시 null
     */
    private String registerInstance(String hubUrl, String instanceId) {
        // V1 API 사용: /hub/api/v1/proxy/datasources/register
        // V1 API는 인스턴스 등록과 datasource 등록을 동시에 처리하므로,
        // 이 메서드는 사용하지 않고 registerDatasource()에서만 처리
        // registerDatasource()에서 hubId를 받아옴
        log.warn("⚠️ registerInstance()는 더 이상 사용되지 않습니다. registerDatasource()에서 hubId를 받아옵니다.");
            return null;
    }
    
    /**
     * Datasource 등록 (hubId와 datasourceId를 동시에 받음)
     * 
     * @return DatasourceInfo (hubId와 datasourceId 포함), 실패 시 null
     */
    private DatasourceRegistrationService.DatasourceInfo registerDatasource() {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String dbProductName = metaData.getDatabaseProductName().toLowerCase();
            // Hub가 기대하는 형식으로 변환
            String dbVendor = normalizeDbVendor(dbProductName);
            String host = extractHostFromUrl(originalUrl);
            int port = extractPortFromUrl(originalUrl);
            String database = connection.getCatalog();
            String schema = extractSchemaName(connection, dbProductName);
            
            // Hub에 Datasource 등록/조회 요청 (hubId와 datasourceId를 동시에 받음)
            DatasourceRegistrationService registrationService = 
                new DatasourceRegistrationService(config.getHubUrl(), config.getInstanceId());
            DatasourceRegistrationService.DatasourceInfo datasourceInfo = registrationService.registerOrGetDatasource(
                dbVendor, host, port, database, schema
            );
            
            if (datasourceInfo != null && datasourceInfo.getDatasourceId() != null) {
                log.info("✅ Datasource 등록 완료: datasourceId={}, displayName={}, hubId={}", 
                    datasourceInfo.getDatasourceId(), datasourceInfo.getDisplayName(), datasourceInfo.getHubId());
                
                // datasourceId 저장
                this.cachedDatasourceId = datasourceInfo.getDatasourceId();
                
                return datasourceInfo;
            } else {
                log.warn("⚠️ Datasource 등록 실패: Hub 연결 불가 또는 응답이 null. hubUrl={}, instanceId={}", 
                    config.getHubUrl(), config.getInstanceId());
                return null;
            }
        } catch (Exception e) {
            log.warn("⚠️ Datasource 등록 실패: hubUrl={}, instanceId={}, error={}", 
                config.getHubUrl(), config.getInstanceId(), e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 스키마를 영구저장소에 저장 및 상태 비교
     */
    private void saveSchemasToStorage() {
        try {
            // 현재 스키마 수집 (정책명 없이)
            List<SchemaMetadata> currentSchemas = schemaCollector.collectSchemas();
            
            if (currentSchemas == null || currentSchemas.isEmpty()) {
                log.debug("📋 수집된 스키마가 없어 저장하지 않습니다.");
                return;
            }
            
            // 정책명을 null로 설정 및 datasourceId 설정
            for (SchemaMetadata schema : currentSchemas) {
                if (schema != null) {
                    schema.setPolicyName(null); // 정책명은 null로 저장
                    // datasourceId 설정 (이미 JdbcSchemaCollector에서 설정되었을 수 있음)
                    if (cachedDatasourceId != null && schema.getDatasourceId() == null) {
                        schema.setDatasourceId(cachedDatasourceId);
                    }
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
     * Hub에 스키마가 동기화되어 있는지 확인하고 필요시 재전송
     * 
     * @param hubId Hub ID
     * @return 스키마 등록 완료 여부 (생성 상태 스키마가 없거나 등록 성공 시 true)
     */
    private boolean ensureSchemasSyncedToHub(String hubId) {
        // 생성 상태 스키마 전송
        List<SchemaMetadata> createdSchemas = schemaStorage.getCreatedSchemas();
        if (!createdSchemas.isEmpty()) {
            log.info("📝 생성 상태 스키마 Hub 전송: hubId={}, 스키마 개수={}", hubId, createdSchemas.size());
            boolean synced = syncCreatedSchemasToHub(hubId, createdSchemas);
            if (synced) {
                // Hub의 /schemas/sync 엔드포인트 응답을 받았으므로 REGISTERED로 변경
                List<String> schemaKeys = new java.util.ArrayList<>();
                for (SchemaMetadata schema : createdSchemas) {
                    if (schema != null) {
                        schemaKeys.add(schema.getKey());
                    }
                }
                schemaStorage.updateSchemasStatus(schemaKeys, SchemaMetadata.Status.REGISTERED);
                log.info("✅ 생성 상태 스키마 전송 완료 및 상태 업데이트: {}개 스키마 (CREATED -> REGISTERED)", 
                        createdSchemas.size());
                return true;  // 스키마 등록 성공
            } else {
                // 스키마 전송 실패: 404 응답 가능성 -> 재등록 필요
                log.info("🔄 생성 상태 스키마 전송 실패 (404 가능성): hubId={}, 재등록 시도", hubId);
                boolean reRegistered = registerWithHub();
                if (reRegistered) {
                    String newHubId = hubIdManager.getCachedHubId(); // HubIdManager에서 최신 hubId 가져오기
                    log.info("✅ 재등록 완료: 새로운 hubId={}", newHubId);
                    // 재등록 후 스키마 재전송 시도
                    return ensureSchemasSyncedToHub(newHubId);
                } else {
                    log.warn("⚠️ 재등록 실패");
                    return false;
                }
            }
        } else {
            log.debug("📋 전송할 스키마 없음, Hub에 이미 동기화된 것으로 간주");
            return true;  // 등록할 스키마가 없으면 완료로 간주
        }
    }
    
    /**
     * 생성 상태 스키마만 Hub에 전송
     */
    private boolean syncCreatedSchemasToHub(String hubId, List<SchemaMetadata> createdSchemas) {
        if (createdSchemas == null || createdSchemas.isEmpty()) {
            return false;
        }
        
        // JdbcSchemaSyncService를 사용하여 스키마 전송
        // hubId를 파라미터로 직접 전달
        // 현재 버전은 null (최초 등록)
        boolean success = schemaSyncService.syncSchemaToHub(hubId, config.getInstanceId(), null);
        
        // 404 응답 처리: false 반환 시 404인지 확인
        if (!success) {
            // RetryableSchemaSyncService에서 404를 확인하고 false를 반환했을 수 있음
            // 여기서는 false만 반환하고, 상위에서 재등록 처리
            log.info("🔄 스키마 동기화 실패 (404 가능성), 재등록 필요");
        }
        
        return success;
    }
    
    /**
     * hubId 획득 후 서비스 초기화
     */
    private void initializeServicesWithHubId(String hubId) {
        // MappingSyncService 초기화
        // V1 API 사용: "/hub/api/v1/proxy"
        this.mappingSyncService = new MappingSyncService(
            config.getHubUrl(),
            hubId,
            config.getInstanceId(),
            cachedDatasourceId,
            "/hub/api/v1/proxy",  // V1 API 경로
            policyResolver
        );
        
        // EndpointSyncService 초기화
        this.endpointSyncService = new EndpointSyncService(
            config.getHubUrl(),
            hubId,
            config.getInstanceId(),
            endpointStorage
        );
        
        // DirectCryptoAdapter 초기화
        this.directCryptoAdapter = new DirectCryptoAdapter(config.isFailOpen());
        
        // 저장된 엔드포인트 정보로 먼저 초기화 (Hub 없이도 동작 가능)
        EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
        if (endpointData != null && endpointData.getCryptoUrl() != null && 
            !endpointData.getCryptoUrl().trim().isEmpty()) {
            directCryptoAdapter.setEndpointData(endpointData);
            log.info("✅ 저장된 엔드포인트 정보로 암복호화 어댑터 초기화 완료: cryptoUrl={}, hubId={}, version={}", 
                    endpointData.getCryptoUrl(), endpointData.getHubId(), endpointData.getVersion());
        }
    }
    
    /**
     * 정책 매핑 동기화 서비스 초기화 (AOP와 동일한 구조)
     */
    private void initializePolicyMappingSyncService(String hubId) {
        try {
            // MappingSyncService와 EndpointSyncService는 이미 initializeServicesWithHubId에서 초기화됨
            // JdbcPolicyMappingSyncService 생성 (Connection과 originalUrl 전달)
            this.policyMappingSyncService = new JdbcPolicyMappingSyncService(
                mappingSyncService,
                endpointSyncService,
                schemaSyncService,
                policyResolver,
                directCryptoAdapter,
                endpointStorage,
                config,
                configStorage,
                schemaStorage,
                cachedDatasourceId,
                connection,
                originalUrl
            );
            
            // 재등록 콜백 설정 (404 응답 시 호출됨)
            final JdbcBootstrapOrchestrator self = this;
            policyMappingSyncService.setReregistrationCallback(() -> {
                log.info("🔄 재등록 콜백 호출: Datasource 재등록 수행");
                // registerWithHub()를 호출하여 Datasource 재등록 및 스키마 재전송
                self.registerWithHub();
            });
            
            log.info("✅ JdbcPolicyMappingSyncService 초기화 완료: hubId={}", hubId);
        } catch (Exception e) {
            log.warn("⚠️ JdbcPolicyMappingSyncService 초기화 실패: {}", e.getMessage());
        }
    }
    
    /**
     * URL에서 호스트 추출
     */
    private String extractHostFromUrl(String url) {
        try {
            int start = url.indexOf("://") + 3;
            int end = url.indexOf(":", start);
            if (end < 0) {
                end = url.indexOf("/", start);
            }
            if (end < 0) {
                end = url.length();
            }
            return url.substring(start, end);
        } catch (Exception e) {
            return "localhost";
        }
    }
    
    /**
     * URL에서 포트 추출
     */
    private int extractPortFromUrl(String url) {
        try {
            int start = url.indexOf("://") + 3;
            int colonIndex = url.indexOf(":", start);
            if (colonIndex < 0) {
                return 3306; // 기본 포트
            }
            int end = url.indexOf("/", colonIndex);
            if (end < 0) {
                end = url.length();
            }
            return Integer.parseInt(url.substring(colonIndex + 1, end));
        } catch (Exception e) {
            return 3306; // 기본 포트
        }
    }
    
    /**
     * DB 벤더명 정규화 (Hub가 기대하는 형식으로 변환)
     */
    private String normalizeDbVendor(String dbProductName) {
        if (dbProductName == null || dbProductName.trim().isEmpty()) {
            return "unknown";
        }
        String lower = dbProductName.toLowerCase();
        if (lower.contains("mysql") || lower.contains("mariadb")) {
            return "mysql";
        } else if (lower.contains("postgresql") || lower.contains("postgres")) {
            return "postgresql";
        } else if (lower.contains("microsoft sql server") || lower.contains("sql server") || lower.contains("mssql")) {
            return "mssql";
        } else if (lower.contains("oracle")) {
            return "oracle";
        }
        return lower; // 알 수 없는 경우 원본 반환
    }
    
    /**
     * DB 벤더별 schemaName 추출
     */
    private String extractSchemaName(Connection connection, String dbProductName) throws SQLException {
        String lower = dbProductName != null ? dbProductName.toLowerCase() : "";
        if (lower.contains("mysql") || lower.contains("mariadb")) {
            return connection.getCatalog();
        } else if (lower.contains("postgresql") || lower.contains("postgres")) {
            String schema = connection.getSchema();
            return schema != null && !schema.isEmpty() ? schema : "public";
        } else if (lower.contains("microsoft sql server") || lower.contains("sql server")) {
            return "dbo";
        } else if (lower.contains("oracle")) {
            String schema = connection.getSchema();
            if (schema == null || schema.isEmpty()) {
                try {
                    schema = connection.getMetaData().getUserName();
                } catch (SQLException e) {
                    log.debug("Oracle userName 조회 실패: {}", e.getMessage());
                }
            }
            return schema;
        }
        return connection.getCatalog();
    }
    
    // Getter 메서드들
    public PolicyResolver getPolicyResolver() {
        return policyResolver;
    }
    
    public MappingSyncService getMappingSyncService() {
        return mappingSyncService;
    }
    
    public EndpointSyncService getEndpointSyncService() {
        return endpointSyncService;
    }
    
    public EndpointStorage getEndpointStorage() {
        return endpointStorage;
    }
    
    public DirectCryptoAdapter getDirectCryptoAdapter() {
        return directCryptoAdapter;
    }
    
    public String getCachedHubId() {
        // HubIdManager에서 전역으로 관리되는 hubId 반환
        return hubIdManager.getCachedHubId();
    }
    
    public String getCachedDatasourceId() {
        return cachedDatasourceId;
    }
    
    public JdbcSchemaSyncService getSchemaSyncService() {
        return schemaSyncService;
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    public JdbcPolicyMappingSyncService getPolicyMappingSyncService() {
        return policyMappingSyncService;
    }
}

