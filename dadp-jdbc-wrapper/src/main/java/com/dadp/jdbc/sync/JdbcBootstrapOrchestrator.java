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
import com.dadp.jdbc.config.ExportedConfigLoader;
import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;
import com.dadp.jdbc.mapping.DatasourceRegistrationService;
import com.dadp.jdbc.notification.HubNotificationService;
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
 * <h2>데이터 출처 (언제·어디서 값을 가져오는지)</h2>
 * <ul>
 *   <li><b>영구저장소에서 가져오는 시점</b>
 *     <ul>
 *       <li>hubId: {@link HubIdManager#loadFromStorage()} → InstanceConfigStorage (proxy-config.json)</li>
 *       <li>정책 매핑·버전: PolicyResolver → PolicyMappingStorage (policy-mappings.json)</li>
 *       <li>엔드포인트: {@link com.dadp.common.sync.config.EndpointStorage#loadEndpoints()} → crypto-endpoints.json</li>
 *       <li>스키마 목록: SchemaStorage.loadSchemas() → schemas.json</li>
 *       <li>datasourceId: DatasourceStorage.loadDatasourceId() → DB 키(host,port,db,schema)로 로컬 파일 조회</li>
 *     </ul>
 *     → 2단계 loadOtherDataFromPersistentStorage() 및 “이미 실행됨” 분기에서 loadFromStorage() 시 호출.</li>
 *   <li><b>DB로부터 얻어오는 시점</b>
 *     <ul>
 *       <li>connection.getMetaData(), getCatalog(), getSchema() → dbVendor, database, schema, host/port 추출</li>
 *       <li>schemaCollector.collectSchemas() → SchemaRecognizer가 JDBC Connection으로 테이블/컬럼 메타데이터 수집</li>
 *     </ul>
 *     → 1단계 collectSchemasWithRetry, 2단계 loadOtherDataFromPersistentStorage, 3단계 saveSchemasToStorage(currentSchemas), registerDatasource() 내부.</li>
 *   <li><b>Hub로부터 받아오는 시점</b>
 *     <ul>
 *       <li>Datasource 등록: registerOrGetDatasource() → hubId, datasourceId 응답</li>
 *       <li>스키마 전송: syncSpecificSchemasToHub() → Hub가 스키마 저장 (Wrapper→Hub 방향)</li>
 *       <li>정책 매핑·엔드포인트: JdbcPolicyMappingSyncService 주기 동기화에서 Hub API로 로드 후 영구저장소에 저장</li>
 *     </ul>
 *     → 3단계 registerWithHub() 및 ensureSchemasSyncedToHub(), 5단계 이후 주기 동기화.</li>
 * </ul>
 * 
 * <p><b>논리 순서 (부팅)</b>: 1) DB 스키마 1회 수집 2) 영구저장소 로드 3) 저장소 vs 수집 스키마 비교·저장 4) hubId 없으면 Hub에서 획득 5) 생성 스키마 Hub 등록 → 초기화 종료. 반복(매핑 체크): 304=동기화 완료, 200=동기화 수행, 404=인스턴스 없음→재등록.</p>
 * 
 * @author DADP Development Team
 * @version 5.2.2
 * @since 2026-01-08
 */
public class JdbcBootstrapOrchestrator {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(JdbcBootstrapOrchestrator.class);
    
    // instanceId별 1회 실행 보장 (static으로 전역 관리)
    private static final ConcurrentHashMap<String, AtomicBoolean> instanceStartedMap = new ConcurrentHashMap<>();
    
    // instanceId당 오케스트레이터 1세트 공유 (static 캐시)
    private static final ConcurrentHashMap<String, JdbcBootstrapOrchestrator> orchestratorByInstanceId = new ConcurrentHashMap<>();
    
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
    private final InstanceIdProvider instanceIdProvider; // core에서 제공하는 instanceId 관리
    
    // Wrapper 전용
    private JdbcSchemaSyncService schemaSyncService;
    private JdbcSchemaCollector schemaCollector;
    private final ProxyConfig config;
    private final String originalUrl;
    
    // 첫 부팅 시 Connection에서 추출한 메타데이터 (재등록·이미 실행됨 분기에서 Connection 없이 사용)
    private volatile String storedDbVendor;
    private volatile String storedHost;
    private volatile int storedPort;
    private volatile String storedDatabase;
    private volatile String storedSchema;
    
    // 정책 매핑 동기화 서비스 (AOP와 동일한 구조)
    private JdbcPolicyMappingSyncService policyMappingSyncService;
    
    // Hub 알림 서비스 (instanceId당 1개 공유, 커넥션 풀에서 재사용)
    private volatile HubNotificationService notificationService;
    
    // 초기화 완료 플래그
    private volatile boolean initialized = false;
    private volatile String cachedDatasourceId = null;
    // hubId는 HubIdManager에서 전역으로 관리 (cachedHubId 필드 제거)
    
    /**
     * 생성자 (Connection 없음, instanceId당 1세트 공유 시 사용).
     * runBootstrapFlow(Connection) 호출 시 첫 부팅에서만 Connection 사용.
     */
    public JdbcBootstrapOrchestrator(String originalUrl, ProxyConfig config) {
        this.originalUrl = originalUrl;
        this.config = config;
        
        // HubIdManager 초기화 (전역 hubId 관리)
        java.util.Map<String, String> urlParams = config.getUrlParams();
        this.instanceIdProvider = new InstanceIdProvider(urlParams);
        String instanceId = this.instanceIdProvider.getInstanceId();
        
        // InstanceConfigStorage 초기화 (instanceId 사용)
        this.configStorage = new InstanceConfigStorage(
            System.getProperty("user.dir") + "/dadp/wrapper/" + instanceId, 
            "proxy-config.json"
        );
        
        // SchemaStorage 초기화 (instanceId 사용)
        this.schemaStorage = new SchemaStorage(instanceId);
        this.hubIdManager = new HubIdManager(
            configStorage,
            config.getHubUrl(),
            instanceIdProvider,
            (oldHubId, newHubId) -> {
                // hubId 변경 시 MappingSyncService 재생성
                if (newHubId != null && !newHubId.equals(oldHubId)) {
                    log.debug("hubId changed: {} -> {}, recreating MappingSyncService", oldHubId, newHubId);
                    initializeServicesWithHubId(newHubId);
                }
            }
        );
        
        // PolicyResolver 초기화 (싱글톤)
        this.policyResolver = PolicyResolver.getInstance();
        
        // EndpointStorage 초기화 (instanceId를 사용하여 경로 생성: ./dadp/wrapper/instanceId)
        this.endpointStorage = new EndpointStorage(instanceId);
        
        // 스키마 수집기 초기화 (Connection 필드 없음, collectSchemas(Connection) 호출 시점에 전달)
        this.schemaCollector = new JdbcSchemaCollector(null, config);
        
        // 스키마 동기화 서비스 초기화 (V1 API 사용: /hub/api/v1/proxy)
        this.schemaSyncService = new JdbcSchemaSyncService(
            config.getHubUrl(),
            schemaCollector,
            "/hub/api/v1/proxy",  // V1 API 경로
            config,
            policyResolver,
            hubIdManager,
            5,      // maxRetries
            3000,   // initialDelayMs
            2000    // backoffMs
        );
        
        // MappingSyncService와 EndpointSyncService는 hubId가 필요하므로 나중에 초기화
    }
    
    /**
     * instanceId당 오케스트레이터 1세트 공유: 캐시에서 조회 또는 생성.
     *
     * @param instanceId 인스턴스 별칭 (JDBC URL에서 추출)
     * @param originalUrl JDBC URL
     * @param config Proxy 설정
     * @return 해당 instanceId의 오케스트레이터 (공유)
     */
    public static JdbcBootstrapOrchestrator getOrCreate(String instanceId, String originalUrl, ProxyConfig config) {
        return orchestratorByInstanceId.computeIfAbsent(instanceId, k -> new JdbcBootstrapOrchestrator(originalUrl, config));
    }
    
    /**
     * Connection에서 메타데이터 추출 후 저장 (첫 부팅 1회, 재등록·이미 실행됨 분기에서 사용).
     */
    private void storeMetadataFrom(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String dbProductName = metaData.getDatabaseProductName().toLowerCase();
            storedDbVendor = normalizeDbVendor(dbProductName);
            storedHost = extractHostFromUrl(originalUrl, storedDbVendor);
            storedPort = extractPortFromUrl(originalUrl, storedDbVendor);
            storedDatabase = connection.getCatalog();
            storedSchema = extractSchemaName(connection, dbProductName);

            // Oracle: getCatalog()이 null을 반환하므로 서비스명 또는 스키마로 대체
            if ((storedDatabase == null || storedDatabase.trim().isEmpty()) && "oracle".equals(storedDbVendor)) {
                storedDatabase = extractDatabaseFromOracleUrl(originalUrl);
                if (storedDatabase == null || storedDatabase.trim().isEmpty()) {
                    storedDatabase = storedSchema; // 스키마를 database로 사용
                }
                log.debug("Oracle database fallback value set: {}", storedDatabase);
            }
        } catch (Exception e) {
            log.debug("Metadata extraction failed (ignored): {}", e.getMessage());
        }
    }
    
    /** 저장된 메타데이터로 datasourceId 로드 시 사용 (이미 실행됨/재등록 시 Connection 없이 사용) */
    public String getStoredDbVendor() { return storedDbVendor; }
    public String getStoredHost() { return storedHost; }
    public int getStoredPort() { return storedPort; }
    public String getStoredDatabase() { return storedDatabase; }
    public String getStoredSchema() { return storedSchema; }
    public String getStoredOriginalUrl() { return originalUrl; }
    public boolean hasStoredMetadata() { return storedDbVendor != null && storedHost != null && storedDatabase != null; }
    
    /**
     * 부팅 플로우 실행. instanceId당 1세트 공유 시 첫 커넥션에서만 Connection 사용.
     *
     * @param connection JDBC Connection (첫 부팅 시 스키마 수집·메타데이터 추출에만 사용, 저장하지 않음)
     * @return 초기화 완료 여부
     */
    public boolean runBootstrapFlow(Connection connection) {
        // instanceId 기반으로 전역 1회 실행 보장 (core의 InstanceIdProvider 사용)
        String instanceId = instanceIdProvider.getInstanceId();
        AtomicBoolean instanceStarted = instanceStartedMap.computeIfAbsent(instanceId, k -> new AtomicBoolean(false));
        
        if (!instanceStarted.compareAndSet(false, true)) {
            log.trace("JdbcBootstrapOrchestrator already executed (instanceId={})", instanceId);
            // 이미 실행된 경우: 서비스는 첫 부팅에서 이미 초기화됨. 재초기화하지 않음 (커넥션마다 HubNotificationService 등 중복 생성 방지)
            String loadedHubId = hubIdManager.loadFromStorage();
            if (loadedHubId != null && !loadedHubId.trim().isEmpty()) {
                this.initialized = true;
                // datasourceId는 저장된 메타데이터로 로드 (Connection 사용 안 함)
                if (hasStoredMetadata()) {
                    try {
                        String cached = com.dadp.jdbc.config.DatasourceStorage.loadDatasourceId(
                            storedDbVendor, storedHost, storedPort, storedDatabase, storedSchema);
                        if (cached != null && !cached.trim().isEmpty()) {
                            this.cachedDatasourceId = cached;
                        }
                    } catch (Exception e) {
                        log.debug("datasourceId load failed (ignored): {}", e.getMessage());
                    }
                }
                return true;
            }
            // hubId가 없으면 초기화 실패로 간주
            return false;
        }
        
        // 인스턴스별 실행 플래그도 설정
        if (!started.compareAndSet(false, true)) {
            log.trace("This instance has already been executed.");
            return initialized;
        }
        
        try {
            // Hub URL이 없으면 실행하지 않음
            String hubUrl = config.getHubUrl();
            if (hubUrl == null || hubUrl.trim().isEmpty()) {
                log.debug("Hub URL not configured, skipping bootstrap flow.");
                return false;
            }
            
            log.info("JDBC Wrapper bootstrap flow orchestrator starting");
            
            // Connection에서 메타데이터 추출·저장 (재등록·이미 실행됨 분기에서 Connection 없이 사용)
            storeMetadataFrom(connection);
            
            // 1. DB 스키마 1회 수집 (호출 시점에 Connection 전달, 필드로 보관하지 않음)
            log.info("Step 1: DB schema collection (one-time)");
            List<SchemaMetadata> currentSchemas = schemaSyncService.collectSchemasWithRetry(connection, 5, 2000);
            if (currentSchemas == null || currentSchemas.isEmpty()) {
                log.warn("Schema collection failed or returned 0 (continuing in fail-open mode)");
            } else {
                log.info("Schema collection completed: {} schemas", currentSchemas.size());
            }
            
            // 2. 영구저장소 로드 (hubId, 정책매핑, 엔드포인트, 스키마 목록, datasourceId 등)
            log.info("Step 2: Loading data from persistent storage");
            String hubId = hubIdManager.loadFromStorage();
            loadOtherDataFromPersistentStorage();
            
            // 2.5. Try loading from exported config file (initial bootstrap or policy update)
            // ExportedConfigLoader internally compares policyVersion and skips if current >= file
            {
                String storageDir = System.getProperty("user.dir") + "/dadp/wrapper/" + instanceId;
                String exportedDatasourceId = ExportedConfigLoader.loadIfExists(
                    storageDir,
                    instanceId,
                    hubIdManager,
                    policyResolver,
                    endpointStorage
                );
                if (exportedDatasourceId != null) {
                    hubId = hubIdManager.getCachedHubId();
                    this.cachedDatasourceId = exportedDatasourceId;
                    log.info("Step 2.5: Applied exported config: hubId={}, datasourceId={}",
                            hubId, exportedDatasourceId);
                }
            }

            // 3. 영구저장소 DB 스키마 vs 1단계 수집 결과 비교 (생성/등록/삭제 판단), 저장
            if (currentSchemas != null && !currentSchemas.isEmpty()) {
                saveSchemasToStorage(currentSchemas);
            }

            // 3. Hub 등록 및 스키마 등록 (hubId가 없으면 등록, 있으면 스키마만 동기화)
            log.info("Step 3: Hub registration and schema registration");
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
                    log.info("hubId changed due to re-registration: {} -> {}", oldHubId, newHubId);
                    hubId = newHubId;
                }
            }
            
            // hubId가 없으면 다음 단계 진행 불가
            if (hubId == null || hubId.trim().isEmpty()) {
                log.warn("Cannot initialize services without hubId.");
                initialized = false;
                return false;
            }
            
            // HubIdManager에 hubId 설정 (전역 관리)
            hubIdManager.setHubId(hubId, true);
            
            // 4. 서비스 초기화 (hubId가 있으면 암복호화 서비스는 항상 초기화)
            // 중요: Hub 등록이 실패해도 저장된 hubId와 엔드포인트 정보로 암복호화는 가능해야 함
            log.info("Step 4: Service initialization (crypto service initialized regardless of Hub registration result)");
            initializeServicesWithHubId(hubId);
            
            // 5. 정책 매핑 동기화 서비스 초기화 (스키마 등록이 완료된 경우에만)
            if (schemaRegistrationCompleted) {
                log.info("Step 5: Policy mapping sync service initialization");
                initializePolicyMappingSyncService(hubId);
                
                // 6. 스키마 등록 완료 후 정책 매핑 동기화 서비스 활성화 (30초 주기 버전 체크 시작)
                initialized = true;
                if (policyMappingSyncService != null) {
                    policyMappingSyncService.setInitialized(true, hubId);
                }
                log.info("JDBC Wrapper bootstrap flow completed: hubId={}, datasourceId={}", hubIdManager.getCachedHubId(), cachedDatasourceId);
            } else {
                // Hub 등록이 실패했지만 저장된 hubId로 암복호화 서비스는 초기화됨
                // 정책 매핑 동기화는 나중에 Hub 연결이 복구되면 재시도됨
                log.warn("Hub registration failed: crypto service initialized but policy mapping sync not started. Will retry when Hub connection is restored.");
                initialized = true; // 암복호화 서비스는 사용 가능하므로 초기화 완료로 간주
                log.info("JDBC Wrapper bootstrap flow completed (limited): hubId={}, datasourceId={}, crypto available",
                        hubIdManager.getCachedHubId(), cachedDatasourceId);
            }
            return true;
            
        } catch (Exception e) {
            // 예측 가능한 문제: 부팅 플로우 실패 (Hub 연결 불가 등)
            // 스택 트레이스 출력 금지 (exception-handling.md 규약)
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.trim().isEmpty()) {
                errorMessage = e.getClass().getSimpleName();
            }
            log.warn("Bootstrap flow failed: {}", errorMessage);
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
            log.debug("Policy mappings loaded from persistent storage: version={}", loadedPolicyVersion);
        }
        
        // EndpointStorage에서 엔드포인트 정보 로드
        EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
        if (endpointData != null) {
            log.debug("Endpoint info loaded from persistent storage: cryptoUrl={}, hubId={}, version={}",
                    endpointData.getCryptoUrl(), endpointData.getHubId(), endpointData.getVersion());
        }
        
        // SchemaStorage에서 스키마 로드
        List<SchemaMetadata> storedSchemas = schemaStorage.loadSchemas();
        if (!storedSchemas.isEmpty()) {
            log.debug("Schemas loaded from persistent storage: {} schemas", storedSchemas.size());
        }
        
        // DatasourceStorage에서 datasourceId 로드 (저장된 메타데이터 사용, Connection 없음)
        if (hasStoredMetadata()) {
            try {
                String cached = com.dadp.jdbc.config.DatasourceStorage.loadDatasourceId(
                    storedDbVendor, storedHost, storedPort, storedDatabase, storedSchema);
                if (cached != null && !cached.trim().isEmpty()) {
                    this.cachedDatasourceId = cached;
                    log.debug("Stored datasourceId loaded: datasourceId={}", this.cachedDatasourceId);
                }
            } catch (Exception e) {
                log.warn("Failed to load datasourceId: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Hub에 등록 (V1 API: Datasource 등록에서 hubId와 datasourceId를 동시에 받음)
     * 
     * @return 스키마 등록 완료 여부 (hubId 등록 및 스키마 등록 성공 시 true)
     */
    private boolean registerWithHub() {
        String instanceId = instanceIdProvider.getInstanceId();
        
        // V1 API: Datasource 등록 (인증서 확인/다운로드 없음, HTTP Hub 또는 기본 신뢰 저장소 사용)
        log.info("Hub Datasource registration starting: instanceId={}", instanceId);
        DatasourceRegistrationService.DatasourceInfo datasourceInfo = registerDatasource(null);
        if (datasourceInfo == null) {
            log.warn("Datasource registration failed: Hub unreachable or response error");
            return false;
        }
        
        // hubId와 datasourceId 저장
        String hubId = datasourceInfo.getHubId();
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("Datasource registration response missing hubId");
            return false;
        }
        
        // HubIdManager에 hubId 설정 (전역 관리, 영구저장소에 자동 저장)
        hubIdManager.setHubId(hubId, true);
        log.info("Hub Datasource registration completed: hubId={}, datasourceId={}", hubId, datasourceInfo.getDatasourceId());
        
        // EndpointSyncService 초기화 (instanceId를 사용하여 경로 생성)
        String endpointStorageDir = System.getProperty("user.dir") + "/dadp/wrapper/" + instanceId;
        String endpointFileName = "crypto-endpoints.json";
        this.endpointSyncService = new EndpointSyncService(
            config.getHubUrl(),
            hubId,
            instanceId,
            endpointStorageDir,
            endpointFileName
        );
        
        // datasourceId가 설정된 후 schemaCollector와 schemaSyncService 재생성 (Connection 필드 없음)
        if (cachedDatasourceId != null && !cachedDatasourceId.trim().isEmpty()) {
            this.schemaCollector = new JdbcSchemaCollector(cachedDatasourceId, config);
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
            log.debug("schemaCollector recreated after datasourceId set: datasourceId={}", cachedDatasourceId);
        }
        
        // 저장된 스키마에 datasourceId 업데이트 (Datasource 등록 전에 저장된 스키마에 datasourceId가 없을 수 있음)
        if (cachedDatasourceId != null && !cachedDatasourceId.trim().isEmpty()) {
            List<SchemaMetadata> allStoredSchemas = schemaStorage.loadSchemas();
            boolean needsUpdate = false;
            for (SchemaMetadata schema : allStoredSchemas) {
                if (schema != null && (schema.getDatasourceId() == null || schema.getDatasourceId().trim().isEmpty())) {
                    schema.setDatasourceId(cachedDatasourceId);
                    needsUpdate = true;
                }
            }
            if (needsUpdate) {
                schemaStorage.saveSchemas(allStoredSchemas);
                log.info("Stored schemas updated with datasourceId: datasourceId={}, schemaCount={}",
                    cachedDatasourceId, allStoredSchemas.size());
            }
        }
        
        // 3단계: 생성 상태 스키마 전송 (AOP와 동일한 구조)
        if (schemaSyncService == null) {
            log.warn("JdbcSchemaSyncService unavailable, cannot perform schema sync.");
            return false;
        }
        
        List<SchemaMetadata> createdSchemas = schemaStorage.getCreatedSchemas();
        if (!createdSchemas.isEmpty()) {
            log.info("Step 3: Sending CREATED schemas to Hub: hubId={}, schemaCount={}", hubId, createdSchemas.size());
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
                log.info("CREATED schemas sent and status updated: {} schemas (CREATED -> REGISTERED)", updatedCount);
                log.info("Hub registration completed: hubId={}", hubId);
                return true;  // 스키마 등록 성공
            } else {
                log.warn("CREATED schemas send failed (no Hub response)");
                return false;  // 스키마 등록 실패
            }
        } else {
            log.info("Step 3: No CREATED schemas (only already-registered schemas exist)");
        log.info("Hub registration completed: hubId={}", hubId);
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
        log.warn("registerInstance() is deprecated. Use registerDatasource() to obtain hubId.");
            return null;
    }
    
    /**
     * Datasource 등록 (hubId와 datasourceId를 동시에 받음)
     * 
     * @param caCertPath Root CA 인증서 경로 (null이면 HTTP/기본 신뢰 저장소 사용)
     * @return DatasourceInfo (hubId와 datasourceId 포함), 실패 시 null
     */
    private DatasourceRegistrationService.DatasourceInfo registerDatasource(String caCertPath) {
        try {
            // 저장된 메타데이터 사용 (재등록·첫 부팅 모두, Connection 필드 없음)
            if (!hasStoredMetadata()) {
                log.warn("No stored metadata: skipping registerDatasource");
                return null;
            }
            String dbVendor = storedDbVendor;
            String host = storedHost;
            int port = storedPort;
            String database = storedDatabase;
            String schema = storedSchema;
            
            // Hub에 Datasource 등록/조회 요청 (hubId와 datasourceId를 동시에 받음)
            // 재등록 시 Hub가 hubVersion = currentVersion + 1로 설정할 수 있도록 currentVersion 전송
            Long currentVersion = policyResolver.getCurrentVersion();
            if (currentVersion == null) {
                currentVersion = 0L;
            }
            
            DatasourceRegistrationService registrationService = 
                new DatasourceRegistrationService(config.getHubUrl(), instanceIdProvider.getInstanceId(), caCertPath);
            DatasourceRegistrationService.DatasourceInfo datasourceInfo = registrationService.registerOrGetDatasource(
                dbVendor, host, port, database, schema, currentVersion
            );
            
            if (datasourceInfo != null && datasourceInfo.getDatasourceId() != null) {
                log.info("Datasource registration completed: datasourceId={}, displayName={}, hubId={}",
                    datasourceInfo.getDatasourceId(), datasourceInfo.getDisplayName(), datasourceInfo.getHubId());
                
                // datasourceId 저장
                this.cachedDatasourceId = datasourceInfo.getDatasourceId();
                
                return datasourceInfo;
            } else {
                log.warn("Datasource registration failed: Hub unreachable or null response. hubUrl={}, instanceId={}",
                    config.getHubUrl(), instanceIdProvider.getInstanceId());
                return null;
            }
        } catch (Exception e) {
            log.warn("Datasource registration failed: hubUrl={}, instanceId={}, error={}",
                config.getHubUrl(), instanceIdProvider.getInstanceId(), e.getMessage());
            return null;
        }
    }
    
    /**
     * Root CA 인증서 확인 및 설정
     * 
     * 플로우:
     * 1. 수동 경로(DADP_CA_CERT_PATH / dadp.ca.cert.path) 확인
     * 2. 저장소에 기존 인증서 파일 확인
     * 3. 검증 후 반환 (다운로드는 하지 않음)
     * 
     * @param hubUrl Hub URL
     * @param instanceId 인스턴스 ID
     * @return 인증서 파일 경로 (검증 완료 시 경로, 없거나 실패 시 null)
     */
    private String ensureRootCACertificate(String hubUrl, String instanceId) {
        log.info("Root CA certificate verification starting: hubUrl={}, instanceId={}", hubUrl, instanceId);
        
        // DADP_CA_CERT_PATH가 수동으로 설정되어 있으면 그것을 사용 (최우선)
        String manualCaCertPath = System.getProperty("dadp.ca.cert.path");
        if (manualCaCertPath == null || manualCaCertPath.trim().isEmpty()) {
            manualCaCertPath = System.getenv("DADP_CA_CERT_PATH");
        }
        if (manualCaCertPath != null && !manualCaCertPath.trim().isEmpty()) {
            // 수동 설정된 인증서도 검증 필요
            java.nio.file.Path certPath = java.nio.file.Paths.get(manualCaCertPath);
            if (java.nio.file.Files.exists(certPath)) {
                if (validateRootCACertificate(certPath)) {
                    log.info("Manually configured Root CA certificate verified: path={}", manualCaCertPath);
                    return manualCaCertPath;
                } else {
                    log.warn("Manually configured Root CA certificate verification failed: path={}", manualCaCertPath);
                    return null;
                }
            } else {
                log.warn("Manually configured Root CA certificate file does not exist: path={}", manualCaCertPath);
                return null;
            }
        }
        
        java.nio.file.Path wrapperDir = java.nio.file.Paths.get(
            System.getProperty("user.dir"), "dadp", "wrapper", instanceId);
        java.nio.file.Path caCertPath = wrapperDir.resolve("dadp-root-ca.crt");
        
        log.debug("Root CA certificate storage path: {}", caCertPath.toAbsolutePath());
        
        try {
            // 저장소에 인증서 확인 (다운로드 없음)
            boolean certExists = java.nio.file.Files.exists(caCertPath);
            
            if (certExists) {
                log.info("Root CA certificate found in storage: path={}", caCertPath);
            } else {
                log.info("Root CA certificate not found in storage (manual config or file placement required): path={}", caCertPath);
                return null;
            }
            
            // 검증
            if (validateRootCACertificate(caCertPath)) {
                String certPathStr = caCertPath.toAbsolutePath().toString();
                log.info("Root CA certificate verified: path={}", certPathStr);
                
                if (verifySSLContextCreation(certPathStr)) {
                    log.info("SSLContext creation verified with Root CA certificate: path={}", certPathStr);
                    return certPathStr;
                } else {
                    log.warn("SSLContext creation failed with Root CA certificate: path={}", certPathStr);
                    return null;
                }
            } else {
                log.warn("Root CA certificate verification failed: path={}", caCertPath);
                try {
                    java.nio.file.Files.deleteIfExists(caCertPath);
                } catch (Exception deleteEx) {
                    log.warn("Failed to delete Root CA certificate file: error={}", deleteEx.getMessage());
                }
                return null;
            }
            
        } catch (Exception e) {
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.trim().isEmpty()) {
                errorMessage = e.getClass().getSimpleName();
            }
            log.warn("Root CA certificate setup failed: error={}", errorMessage);
            return null;
        }
    }
    
    /**
     * SSLContext 생성 검증
     * 
     * 인증서 파일로 실제로 SSLContext를 생성할 수 있는지 확인합니다.
     * 
     * @param caCertPath 인증서 파일 경로
     * @return SSLContext 생성 성공 여부
     */
    private boolean verifySSLContextCreation(String caCertPath) {
        try {
            // 인증서 파일 읽기
            String pem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(caCertPath)), "UTF-8");
            if (pem == null || pem.trim().isEmpty()) {
                log.warn("SSLContext creation verification failed: certificate file is empty");
                return false;
            }
            
            // PEM 형식 인증서를 X.509 인증서로 변환
            String certContent = pem.replace("-----BEGIN CERTIFICATE-----", "")
                                    .replace("-----END CERTIFICATE-----", "")
                                    .replaceAll("\\s", "");
            byte[] certBytes = java.util.Base64.getDecoder().decode(certContent);
            java.security.cert.CertificateFactory certFactory = 
                java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.X509Certificate caCert = 
                (java.security.cert.X509Certificate) certFactory.generateCertificate(
                    new java.io.ByteArrayInputStream(certBytes));
            
            // TrustStore 생성 및 DADP CA 추가
            java.security.KeyStore trustStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("dadp-root-ca", caCert);
            
            // TrustManagerFactory 생성
            javax.net.ssl.TrustManagerFactory trustManagerFactory = 
                javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            
            // SSLContext 생성
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), new java.security.SecureRandom());
            
            // SSLContext 생성 성공
            return true;
        } catch (Exception e) {
            // SSLContext 생성 실패
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.trim().isEmpty()) {
                errorMessage = e.getClass().getSimpleName();
            }
            log.warn("SSLContext creation verification failed: error={}", errorMessage);
            return false;
        }
    }
    
    /**
     * Root CA 인증서 유효성 검증
     * 
     * @param certPath 인증서 파일 경로
     * @return 유효하면 true, 유효하지 않으면 false
     */
    private boolean validateRootCACertificate(java.nio.file.Path certPath) {
        try {
            // 파일 읽기
            String pem = new String(java.nio.file.Files.readAllBytes(certPath), "UTF-8");
            if (pem == null || pem.trim().isEmpty()) {
                log.warn("Root CA certificate file is empty");
                return false;
            }
            
            // PEM 형식 인증서를 X.509 인증서로 변환
            String certContent = pem.replace("-----BEGIN CERTIFICATE-----", "")
                                    .replace("-----END CERTIFICATE-----", "")
                                    .replaceAll("\\s", "");
            
            if (certContent.isEmpty()) {
                log.warn("Root CA certificate PEM format is invalid");
                return false;
            }
            
            byte[] certBytes = java.util.Base64.getDecoder().decode(certContent);
            java.security.cert.CertificateFactory certFactory = 
                java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.X509Certificate cert = 
                (java.security.cert.X509Certificate) certFactory.generateCertificate(
                    new java.io.ByteArrayInputStream(certBytes));
            
            // 유효기간 검증
            cert.checkValidity();
            
            log.debug("Root CA certificate verified: Subject={}, Valid From={}, Valid To={}",
                cert.getSubjectX500Principal().getName(),
                cert.getNotBefore(),
                cert.getNotAfter());
            
            return true;
        } catch (java.security.cert.CertificateExpiredException e) {
            log.warn("Root CA certificate has expired: {}", e.getMessage());
            return false;
        } catch (java.security.cert.CertificateNotYetValidException e) {
            log.warn("Root CA certificate is not yet valid: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Root CA certificate validation failed: error={}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 1단계에서 수집한 스키마를 영구저장소와 비교 후 저장 (DB 재수집 없음).
     *
     * @param currentSchemas 1단계 collectSchemasWithRetry() 결과 (null이면 무시)
     */
    private void saveSchemasToStorage(List<SchemaMetadata> currentSchemas) {
        if (currentSchemas == null || currentSchemas.isEmpty()) {
            log.debug("No collected schemas to save.");
            return;
        }
        try {
            for (SchemaMetadata schema : currentSchemas) {
                if (schema != null) {
                    schema.setPolicyName(null);
                    if (cachedDatasourceId != null && schema.getDatasourceId() == null) {
                        schema.setDatasourceId(cachedDatasourceId);
                    }
                }
            }
            int updatedCount = schemaStorage.compareAndUpdateSchemas(currentSchemas);
            log.info("Schemas saved to persistent storage and status updated: {} schemas updated", updatedCount);
        } catch (Exception e) {
            log.warn("Schema save failed: {}", e.getMessage());
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
            log.info("Sending CREATED schemas to Hub: hubId={}, schemaCount={}", hubId, createdSchemas.size());
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
                log.info("CREATED schemas sent and status updated: {} schemas (CREATED -> REGISTERED)",
                        createdSchemas.size());
                return true;  // 스키마 등록 성공
            } else {
                // 스키마 전송 실패: 404 응답 가능성 -> 재등록 필요
                log.info("CREATED schemas send failed (possible 404): hubId={}, attempting re-registration", hubId);
                boolean reRegistered = registerWithHub();
                if (reRegistered) {
                    String newHubId = hubIdManager.getCachedHubId(); // HubIdManager에서 최신 hubId 가져오기
                    log.info("Re-registration completed: new hubId={}", newHubId);
                    // 재등록 후 스키마 재전송 시도
                    return ensureSchemasSyncedToHub(newHubId);
                } else {
                    log.warn("Re-registration failed");
                    return false;
                }
            }
        } else {
            log.debug("No schemas to send, assumed already synced with Hub");
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
        
        // 전송 전에 datasourceId 설정 (저장된 스키마에 datasourceId가 없을 수 있음)
        if (cachedDatasourceId != null && !cachedDatasourceId.trim().isEmpty()) {
            for (SchemaMetadata schema : createdSchemas) {
                if (schema != null && (schema.getDatasourceId() == null || schema.getDatasourceId().trim().isEmpty())) {
                    schema.setDatasourceId(cachedDatasourceId);
                    log.trace("Set datasourceId on schema before sending: schema={}.{}.{}, datasourceId={}",
                        schema.getSchemaName(), schema.getTableName(), schema.getColumnName(), cachedDatasourceId);
                }
            }
        }
        
        // 저장된 스키마를 직접 전송 (syncSpecificSchemasToHub 사용)
        // syncSchemaToHub는 schemaCollector에서 새로 수집하므로 사용하지 않음
        boolean success = schemaSyncService.syncSpecificSchemasToHub(createdSchemas);
        
        // 404 응답 처리: false 반환 시 404인지 확인
        if (!success) {
            // RetryableSchemaSyncService에서 404를 확인하고 false를 반환했을 수 있음
            // 여기서는 false만 반환하고, 상위에서 재등록 처리
            log.info("Schema sync failed (possible 404), re-registration required");
        }
        
        return success;
    }
    
    /**
     * hubId 획득 후 서비스 초기화
     */
    private void initializeServicesWithHubId(String hubId) {
        // MappingSyncService 초기화
        // V1 API 사용: "/hub/api/v1/proxy"
        String instanceId = instanceIdProvider.getInstanceId();
        this.mappingSyncService = new MappingSyncService(
            config.getHubUrl(),
            hubId,
            instanceId,
            cachedDatasourceId,
            "/hub/api/v1/proxy",  // V1 API 경로
            policyResolver
        );
        
        // EndpointSyncService 초기화 (instanceId를 사용하여 경로 생성)
        String endpointStorageDir = System.getProperty("user.dir") + "/dadp/wrapper/" + instanceId;
        String endpointFileName = "crypto-endpoints.json";
        this.endpointSyncService = new EndpointSyncService(
            config.getHubUrl(),
            hubId,
            instanceId,
            endpointStorageDir,
            endpointFileName
        );
        
        // DirectCryptoAdapter 초기화
        // 중요: Hub 등록 실패 여부와 무관하게 저장된 엔드포인트 정보로 암복호화 서비스 초기화
        // 이렇게 하면 Hub 등록이 1회 성공한 후 Hub에 문제가 있어도 암복호화는 계속 동작 가능
        this.directCryptoAdapter = new DirectCryptoAdapter(config.isFailOpen());
        
        // 저장된 엔드포인트 정보로 먼저 초기화 (Hub 없이도 동작 가능)
        EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
        if (endpointData != null && endpointData.getCryptoUrl() != null && 
            !endpointData.getCryptoUrl().trim().isEmpty()) {
            directCryptoAdapter.setEndpointData(endpointData);
            log.info("Crypto adapter initialized: cryptoUrl={}, hubId={}, version={}",
                    endpointData.getCryptoUrl(), endpointData.getHubId(), endpointData.getVersion());
        }
        
        // Hub 알림 서비스 1회만 생성 (첫 부팅 시 콜백+4단계에서 두 번 호출될 수 있으므로 null일 때만 생성)
        if (this.notificationService == null) {
            try {
                this.notificationService = new HubNotificationService(
                    config.getHubUrl(),
                    hubId,
                    instanceId,
                    config.isEnableLogging()
                );
                log.debug("Hub notification service initialized (shared): hubId={}", hubId);
            } catch (Exception e) {
                log.warn("Hub notification service initialization failed (ignored): {}", e.getMessage());
                this.notificationService = null;
            }
        }
    }
    
    /**
     * 정책 매핑 동기화 서비스 초기화 (AOP와 동일한 구조)
     */
    private void initializePolicyMappingSyncService(String hubId) {
        try {
            // MappingSyncService와 EndpointSyncService는 이미 initializeServicesWithHubId에서 초기화됨
            // JdbcPolicyMappingSyncService 생성 (재등록 시 저장 메타데이터 사용, Connection 미전달)
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
                cachedDatasourceId
            );
            
            // 재등록 콜백 설정 (404 응답 시 호출됨)
            final JdbcBootstrapOrchestrator self = this;
            policyMappingSyncService.setReregistrationCallback(() -> {
                log.info("Re-registration callback invoked: performing Datasource re-registration");
                // registerWithHub()를 호출하여 Datasource 재등록 및 스키마 재전송
                self.registerWithHub();
            });
            
            log.info("JdbcPolicyMappingSyncService initialized: hubId={}", hubId);
        } catch (Exception e) {
            log.warn("JdbcPolicyMappingSyncService initialization failed: {}", e.getMessage());
        }
    }
    
    /**
     * URL에서 호스트 추출 (Oracle URL 형식 지원)
     *
     * 지원 형식:
     * - MySQL/PostgreSQL: jdbc:dadp:mysql://host:3306/db?hubUrl=...
     * - Oracle thin: jdbc:dadp:oracle:thin:@//host:1521/service?hubUrl=...
     * - Oracle thin SID: jdbc:dadp:oracle:thin:@host:1521:SID?hubUrl=...
     */
    private String extractHostFromUrl(String url, String dbVendor) {
        try {
            // 쿼리 파라미터 제거 (hubUrl의 ://와 혼동 방지)
            String baseUrl = url;
            int queryIdx = url.indexOf('?');
            if (queryIdx > 0) {
                baseUrl = url.substring(0, queryIdx);
            }

            if ("oracle".equals(dbVendor)) {
                // Oracle: @// 또는 @ 이후에서 호스트 추출
                int atIdx = baseUrl.indexOf('@');
                if (atIdx >= 0) {
                    String afterAt = baseUrl.substring(atIdx + 1);
                    // @// 형식 (서비스명)
                    if (afterAt.startsWith("//")) {
                        afterAt = afterAt.substring(2);
                    }
                    // host:port 추출
                    int colonIdx = afterAt.indexOf(':');
                    if (colonIdx > 0) {
                        return afterAt.substring(0, colonIdx);
                    }
                    int slashIdx = afterAt.indexOf('/');
                    if (slashIdx > 0) {
                        return afterAt.substring(0, slashIdx);
                    }
                    return afterAt;
                }
            }

            // 기본 (MySQL, PostgreSQL 등): ://host:port 형식
            int start = baseUrl.indexOf("://") + 3;
            if (start < 3) {
                return "localhost";
            }
            int end = baseUrl.indexOf(":", start);
            if (end < 0) {
                end = baseUrl.indexOf("/", start);
            }
            if (end < 0) {
                end = baseUrl.length();
            }
            return baseUrl.substring(start, end);
        } catch (Exception e) {
            return "localhost";
        }
    }

    /**
     * URL에서 포트 추출 (Oracle URL 형식 지원)
     */
    private int extractPortFromUrl(String url, String dbVendor) {
        try {
            // 쿼리 파라미터 제거 (hubUrl의 포트와 혼동 방지)
            String baseUrl = url;
            int queryIdx = url.indexOf('?');
            if (queryIdx > 0) {
                baseUrl = url.substring(0, queryIdx);
            }

            if ("oracle".equals(dbVendor)) {
                // Oracle: @// 또는 @ 이후에서 포트 추출
                int atIdx = baseUrl.indexOf('@');
                if (atIdx >= 0) {
                    String afterAt = baseUrl.substring(atIdx + 1);
                    if (afterAt.startsWith("//")) {
                        afterAt = afterAt.substring(2);
                    }
                    // host:port 에서 port 추출
                    int colonIdx = afterAt.indexOf(':');
                    if (colonIdx >= 0) {
                        String afterColon = afterAt.substring(colonIdx + 1);
                        // port 뒤의 / 또는 : (SID 구분자) 제거
                        int endIdx = afterColon.indexOf('/');
                        int endIdx2 = afterColon.indexOf(':');
                        if (endIdx < 0) endIdx = afterColon.length();
                        if (endIdx2 >= 0 && endIdx2 < endIdx) endIdx = endIdx2;
                        return Integer.parseInt(afterColon.substring(0, endIdx));
                    }
                }
                return 1521; // Oracle 기본 포트
            }

            // 기본 (MySQL, PostgreSQL, MSSQL 등)
            int start = baseUrl.indexOf("://") + 3;
            if (start < 3) {
                return getDefaultPort(dbVendor);
            }
            int colonIndex = baseUrl.indexOf(":", start);
            if (colonIndex < 0) {
                return getDefaultPort(dbVendor);
            }
            String afterColon = baseUrl.substring(colonIndex + 1);
            // 포트 뒤 구분자: / (MySQL, PostgreSQL) 또는 ; (MSSQL) 또는 \ (MSSQL named instance)
            int end = afterColon.length();
            for (int i = 0; i < afterColon.length(); i++) {
                char c = afterColon.charAt(i);
                if (c == '/' || c == ';' || c == '\\') {
                    end = i;
                    break;
                }
            }
            return Integer.parseInt(afterColon.substring(0, end));
        } catch (Exception e) {
            return getDefaultPort(dbVendor);
        }
    }

    /**
     * DB 벤더별 기본 포트 반환
     */
    private int getDefaultPort(String dbVendor) {
        if (dbVendor == null) return 3306;
        switch (dbVendor) {
            case "oracle": return 1521;
            case "postgresql": return 5432;
            case "mssql": return 1433;
            default: return 3306;
        }
    }

    /**
     * Oracle JDBC URL에서 서비스명/SID 추출 (database 대체값)
     *
     * 지원 형식:
     * - jdbc:dadp:oracle:thin:@//host:1521/serviceName → serviceName
     * - jdbc:dadp:oracle:thin:@host:1521:SID → SID
     */
    private String extractDatabaseFromOracleUrl(String url) {
        try {
            String baseUrl = url;
            int queryIdx = url.indexOf('?');
            if (queryIdx > 0) {
                baseUrl = url.substring(0, queryIdx);
            }

            int atIdx = baseUrl.indexOf('@');
            if (atIdx < 0) return null;

            String afterAt = baseUrl.substring(atIdx + 1);

            // @//host:1521/serviceName 형식
            if (afterAt.startsWith("//")) {
                int lastSlash = afterAt.lastIndexOf('/');
                if (lastSlash > 1) {
                    return afterAt.substring(lastSlash + 1);
                }
            }

            // @host:1521:SID 형식
            int lastColon = afterAt.lastIndexOf(':');
            if (lastColon > 0) {
                String candidate = afterAt.substring(lastColon + 1);
                // 포트 번호가 아닌지 확인
                try {
                    Integer.parseInt(candidate);
                    return null; // 숫자면 포트이므로 SID가 아님
                } catch (NumberFormatException e) {
                    return candidate; // 숫자가 아니면 SID
                }
            }

            return null;
        } catch (Exception e) {
            return null;
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
                    log.debug("Failed to retrieve Oracle userName: {}", e.getMessage());
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
    
    /** instanceId당 1개 공유, 커넥션 풀에서 재사용 */
    public HubNotificationService getNotificationService() {
        return notificationService;
    }
}

