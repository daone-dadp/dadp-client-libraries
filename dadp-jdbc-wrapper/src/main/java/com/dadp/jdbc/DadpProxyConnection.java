package com.dadp.jdbc;

import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.jdbc.mapping.DatasourceRegistrationService;
import com.dadp.jdbc.notification.HubNotificationService;
import com.dadp.jdbc.schema.JdbcSchemaSyncService;
import com.dadp.jdbc.schema.JdbcSchemaCollector;
import com.dadp.jdbc.sync.JdbcBootstrapOrchestrator;
// 공통 라이브러리 사용
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.common.sync.mapping.MappingSyncService;
import com.dadp.common.sync.endpoint.EndpointSyncService;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.jdbc.stats.TelemetryStatsSender;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

/**
 * DADP Proxy Connection
 * 
 * 실제 DB Connection을 래핑하여 PreparedStatement와 ResultSet을 가로채어
 * 암복호화 처리를 수행합니다.
 * 
 * @author DADP Development Team
 * @version 4.8.0
 * @since 2025-11-07
 */
public class DadpProxyConnection implements Connection {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(DadpProxyConnection.class);
    
    private final Connection actualConnection;
    private final ProxyConfig config;
    private volatile DirectCryptoAdapter directCryptoAdapter; // 직접 암복호화 어댑터
    private final JdbcSchemaSyncService schemaSyncService;
    private final MappingSyncService mappingSyncService;
    private final EndpointSyncService endpointSyncService; // 엔드포인트 동기화 서비스
    // EndpointStorage는 EndpointSyncService 내부에서 관리됨
    private final TelemetryStatsSender telemetryStatsSender;
    private final PolicyResolver policyResolver;
    private final HubNotificationService notificationService;
    private final String currentDatabaseName;  // 현재 연결된 데이터베이스/스키마명
    private String datasourceId;  // Hub에서 받은 논리 데이터소스 ID
    private boolean closed = false;
    private final JdbcBootstrapOrchestrator orchestrator; // 오케스트레이터 참조 저장 (directCryptoAdapter 업데이트 확인용)
    
    public DadpProxyConnection(Connection actualConnection, String originalUrl) {
        this(actualConnection, originalUrl, null);
    }
    
    public DadpProxyConnection(Connection actualConnection, String originalUrl, Map<String, String> urlParams) {
        this.actualConnection = actualConnection;
        // JDBC URL 파라미터가 있으면 사용, 없으면 싱글톤 인스턴스 사용
        this.config = urlParams != null ? new ProxyConfig(urlParams) : ProxyConfig.getInstance();
        
        
        // 현재 연결된 데이터베이스/스키마명 저장 (Connection에서 가져옴)
        String dbName = null;
        try {
            dbName = actualConnection.getCatalog();  // MySQL: database, PostgreSQL: database
            if (dbName == null || dbName.trim().isEmpty()) {
                // getCatalog()가 null인 경우 스키마 정보 시도 (PostgreSQL 등)
                try {
                    dbName = actualConnection.getSchema();  // PostgreSQL: schema
                } catch (SQLException e) {
                    log.debug("스키마 정보 조회 실패 (무시): {}", e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("⚠️ 현재 데이터베이스명 조회 실패 (무시): {}", e.getMessage());
        }
        this.currentDatabaseName = dbName;
        log.debug("📋 현재 데이터베이스/스키마: {}", currentDatabaseName != null ? currentDatabaseName : "null");
        
        // 오케스트레이터 생성 및 실행
        this.orchestrator = new JdbcBootstrapOrchestrator(
            actualConnection,
            originalUrl,
            config
        );
        
        // 부팅 플로우 실행
        boolean initialized = this.orchestrator.runBootstrapFlow();
        if (!initialized) {
            if (config.isFailOpen()) {
                log.warn("⚠️ 부팅 플로우 실패 (fail-open 모드): 계속 진행합니다.");
            } else {
                throw new RuntimeException("JDBC Wrapper 초기화 실패");
            }
        }
        
        // 오케스트레이터에서 초기화된 서비스 가져오기
        this.policyResolver = this.orchestrator.getPolicyResolver();
        this.mappingSyncService = this.orchestrator.getMappingSyncService();
        this.endpointSyncService = this.orchestrator.getEndpointSyncService();
        this.directCryptoAdapter = this.orchestrator.getDirectCryptoAdapter();
        String hubId = this.orchestrator.getCachedHubId();
        this.datasourceId = this.orchestrator.getCachedDatasourceId();
        
        // hubId가 없으면 외부 요청 차단
        if (hubId == null || hubId.trim().isEmpty()) {
            if (config.isFailOpen()) {
                log.warn("⚠️ hubId가 없지만 fail-open 모드이므로 계속 진행합니다. 외부 요청은 제한될 수 있습니다.");
                // hubId는 null로 유지 (instanceId로 대체하지 않음)
            } else {
                throw new RuntimeException("hubId가 없습니다. Hub 연결을 확인하거나 fail-open 모드를 활성화하세요.");
            }
        }
        
        // 스키마 동기화 서비스는 오케스트레이터에서 생성한 것을 사용 (중복 생성 제거)
        this.schemaSyncService = this.orchestrator.getSchemaSyncService();
        
        // TelemetryStatsSender 초기화 (오케스트레이터에서 가져온 endpointStorage 사용)
        EndpointStorage endpointStorage = this.orchestrator.getEndpointStorage();
        this.telemetryStatsSender = new TelemetryStatsSender(endpointStorage, hubId, this.datasourceId);
        
        // Hub 알림 서비스 초기화 (HubNotificationClient 사용)
        HubNotificationService notificationServiceInstance = null;
        try {
            notificationServiceInstance = new HubNotificationService(config.getHubUrl(), hubId, config.getInstanceId());
            // Connection Pool에서 반복적으로 생성되므로 TRACE 레벨로 처리 (로그 정책 참조)
            log.trace("✅ Hub 알림 서비스 초기화 완료");
        } catch (Exception e) {
            log.warn("⚠️ Hub 알림 서비스 초기화 실패: {}", e.getMessage());
            // null로 설정하여 안전하게 처리 (알림 기능만 비활성화)
        }
        this.notificationService = notificationServiceInstance;
        
        // 주기적 동기화는 오케스트레이터에서 처리하므로 여기서는 제거
        // 기존 loadMappingsFromHub()와 startMappingPolling()은 오케스트레이터에서 처리됨
        
        // Connection Pool에서 반복적으로 생성되므로 TRACE 레벨로 처리 (로그 정책 참조)
        log.trace("✅ DADP Proxy Connection 생성 완료");
    }
    
    /**
     * 기존 데이터 초기화
     * 재등록 시 로컬 상태를 초기화합니다.
     * 
     * @param hubId Hub ID (null 가능)
     * @deprecated 오케스트레이터에서 처리됨
     */
    @Deprecated
    private void resetLocalData(String hubId) {
        // 스키마 해시 캐시 초기화 (재등록 시 강제 동기화를 위해)
        if (hubId != null && schemaSyncService != null) {
            schemaSyncService.clearSchemaHash(hubId);
        }
        log.debug("🔄 로컬 데이터 초기화 완료: hubId={}", hubId);
    }
    
    /**
     * Hub에 등록 및 DB 스키마 전송
     * 
     * @param connection DB 연결
     * @param originalUrl 원본 JDBC URL
     * @return 등록된 hubId (실패 시 null)
     */
    private String registerAndSyncSchema(Connection connection, String originalUrl) {
        // 1. Hub에 등록
        String datasourceId = registerDatasource(connection, originalUrl);
        if (datasourceId == null) {
            log.warn("⚠️ Hub 등록 실패");
            return null;
        }
        this.datasourceId = datasourceId;
        
        // 2. hubId 확인
        String hubId = config.getHubId();
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("⚠️ hubId가 없어 Hub에 재등록을 시도합니다: instanceId={}", config.getInstanceId());
            String retryHubId = retryRegisterProxyInstance(connection, originalUrl);
            if (retryHubId != null && !retryHubId.trim().isEmpty()) {
                hubId = retryHubId;
                // hubId는 HubIdManager에서 전역으로 관리되므로 config.setHubId() 제거
                log.info("✅ Hub에서 hubId 재등록 완료: hubId={}", hubId);
            } else {
                // fail-open 모드에서만 허용
                if (config.isFailOpen()) {
                    hubId = config.getInstanceId();
                    log.warn("⚠️ Hub 연결 실패 (fail-open 모드): instanceId를 임시로 사용합니다: instanceId={}", hubId);
                } else {
                    log.error("❌ Hub에서 hubId를 발급받지 못했습니다. Hub 연결을 확인하거나 fail-open 모드를 활성화하세요.");
                    return null;
                }
            }
        }
        
        // 3. DB 스키마 전송
        syncSchemaMetadata();
        
        return hubId;
    }
    
    /**
     * Datasource 등록 (Hub에서 datasourceId 받아오기)
     * 
     * @param connection DB 연결
     * @param originalUrl 원본 JDBC URL
     * @return Datasource ID (Hub 연결 실패 시 null)
     */
    private String registerDatasource(Connection connection, String originalUrl) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String dbVendor = metaData.getDatabaseProductName().toLowerCase();
            String host = extractHostFromUrl(originalUrl);
            int port = extractPortFromUrl(originalUrl);
            String database = connection.getCatalog();
            String schema = extractSchemaName(connection, dbVendor);
            
            // Hub에 Datasource 등록/조회 요청
            DatasourceRegistrationService registrationService = 
                new DatasourceRegistrationService(config.getHubUrl(), config.getInstanceId());
            DatasourceRegistrationService.DatasourceInfo datasourceInfo = registrationService.registerOrGetDatasource(
                dbVendor, host, port, database, schema
            );
            
            if (datasourceInfo != null && datasourceInfo.getDatasourceId() != null) {
                // hubId는 HubIdManager에서 전역으로 관리되므로 config.setHubId() 제거
                if (datasourceInfo.getHubId() != null && !datasourceInfo.getHubId().trim().isEmpty()) {
                    log.info("✅ Hub가 발급한 고유 ID: hubId={} (HubIdManager에서 관리됨)", datasourceInfo.getHubId());
                }
                log.info("✅ Datasource 등록 완료: datasourceId={}, displayName={}, hubId={}", 
                    datasourceInfo.getDatasourceId(), datasourceInfo.getDisplayName(), datasourceInfo.getHubId());
                return datasourceInfo.getDatasourceId();
            } else {
                // Hub 연결 실패 시 datasourceId 없음
                // 정책이 없으면 암호화/복호화 대상이 없으므로 평문 그대로 통과
                log.debug("Datasource 등록 실패: Hub 연결 불가");
                return null;
            }
        } catch (Exception e) {
            log.warn("⚠️ Datasource 등록 실패: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Proxy Instance 재등록 (hubId가 없을 때)
     * 
     * @param connection DB 연결
     * @param originalUrl 원본 JDBC URL
     * @return hubId (등록 실패 시 null)
     */
    private String retryRegisterProxyInstance(Connection connection, String originalUrl) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String dbVendor = metaData.getDatabaseProductName().toLowerCase();
            String host = extractHostFromUrl(originalUrl);
            int port = extractPortFromUrl(originalUrl);
            String database = connection.getCatalog();
            String schema = extractSchemaName(connection, dbVendor);
            
            // Hub에 Datasource 등록/조회 요청 (hubId 받기)
            DatasourceRegistrationService registrationService = 
                new DatasourceRegistrationService(config.getHubUrl(), config.getInstanceId());
            DatasourceRegistrationService.DatasourceInfo datasourceInfo = registrationService.registerOrGetDatasource(
                dbVendor, host, port, database, schema
            );
            
            if (datasourceInfo != null && datasourceInfo.getHubId() != null && !datasourceInfo.getHubId().trim().isEmpty()) {
                return datasourceInfo.getHubId();
            }
            
            return null;
        } catch (Exception e) {
            log.warn("⚠️ Proxy Instance 재등록 실패: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * URL에서 호스트 추출
     */
    private String extractHostFromUrl(String url) {
        try {
            // jdbc:dadp:mysql://host:port/database 형식
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
     * DB 벤더별 schemaName 추출
     */
    private String extractSchemaName(Connection connection, String dbVendor) throws SQLException {
        if (dbVendor.contains("mysql") || dbVendor.contains("mariadb")) {
            return connection.getCatalog();
        } else if (dbVendor.contains("postgresql")) {
            String schema = connection.getSchema();
            return schema != null && !schema.isEmpty() ? schema : "public";
        } else if (dbVendor.contains("microsoft sql server") || dbVendor.contains("sql server")) {
            return "dbo";
        } else if (dbVendor.contains("oracle")) {
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
    
    /**
     * Datasource ID 조회
     */
    public String getDatasourceId() {
        return datasourceId;
    }
    
    /**
     * 스키마 메타데이터를 Hub로 동기화 (비동기)
     * Proxy Instance별로 한 번만 실행됩니다.
     * @deprecated 오케스트레이터에서 처리됨
     */
    @Deprecated
    private void syncSchemaMetadata() {
        // 오케스트레이터에서 이미 처리됨
        log.trace("⏭️ 스키마 동기화는 오케스트레이터에서 처리됨");
    }
    
    /**
     * Hub에서 정책 매핑 정보를 로드 (비동기, 완료 대기 가능)
     * Proxy Instance별로 한 번만 실행되고, 이후 주기적으로 폴링합니다.
     * @deprecated 오케스트레이터에서 처리됨
     */
    @Deprecated
    private void loadMappingsFromHub() {
        // 오케스트레이터에서 이미 처리됨
        log.trace("⏭️ 정책 매핑 로드는 오케스트레이터에서 처리됨");
    }
    
    /**
     * 정책 매핑 로드가 완료될 때까지 대기
     * @return 정책 로드 완료 여부 (타임아웃 시 false)
     * @deprecated 오케스트레이터에서 처리됨
     */
    @Deprecated
    private boolean waitForMappingsLoaded() {
        // 오케스트레이터에서 이미 처리됨
        return true;
    }
    
    /**
     * 주기적으로 Hub에서 매핑 정보를 폴링
     * Proxy Instance별로 한 번만 스케줄러가 시작됩니다.
     * @deprecated 오케스트레이터에서 처리됨
     */
    @Deprecated
    private void startMappingPolling(String hubId) {
        // 오케스트레이터에서 이미 처리됨
        log.trace("⏭️ 주기적 동기화는 오케스트레이터에서 처리됨");
    }
    
    /**
     * PolicyResolver 반환 (PreparedStatement에서 사용)
     */
    public PolicyResolver getPolicyResolver() {
        return policyResolver;
    }
    
    /**
     * 현재 데이터베이스/스키마명 반환
     * 
     * @return 데이터베이스/스키마명 (없으면 null)
     */
    public String getCurrentDatabaseName() {
        return currentDatabaseName;
    }
    
    /**
     * 매핑 정보 강제 새로고침 (Hub에서 변경 알림 받을 때 사용)
     */
    public void refreshMappings() {
        new Thread(() -> {
            try {
                // 새로운 정책 스냅샷 API 사용 (버전 추적)
                Long currentVersion = policyResolver.getCurrentVersion();
                // 정책 매핑 동기화 및 버전 업데이트 (공통 로직)
                int count = mappingSyncService.syncPolicyMappingsAndUpdateVersion(currentVersion);
                log.info("🔄 정책 매핑 정보 강제 새로고침 완료: {}개 매핑", count);
            } catch (Exception e) {
                log.warn("⚠️ 정책 매핑 정보 새로고침 실패: {}", e.getMessage());
            }
        }, "dadp-proxy-mapping-refresh").start();
    }
    
    /**
     * 직접 암복호화 어댑터 조회 (권장)
     * 
     * @return DirectCryptoAdapter (Engine/Gateway 직접 연결)
     */
    public DirectCryptoAdapter getDirectCryptoAdapter() {
        // 먼저 오케스트레이터의 directCryptoAdapter 확인 (정책 매핑 동기화 후 업데이트되었을 수 있음)
        if (orchestrator != null) {
            DirectCryptoAdapter orchestratorAdapter = orchestrator.getDirectCryptoAdapter();
            if (orchestratorAdapter != null) {
                // 오케스트레이터의 어댑터가 있으면 그것을 사용 (엔드포인트 정보가 설정되어 있을 수 있음)
                if (this.directCryptoAdapter != orchestratorAdapter) {
                    this.directCryptoAdapter = orchestratorAdapter;
                    log.debug("✅ 오케스트레이터의 직접 암복호화 어댑터 사용");
                }
                return this.directCryptoAdapter;
            }
        }
        
        // 지연 초기화: 아직 초기화되지 않았으면 재시도
        if (directCryptoAdapter == null && config.isFailOpen()) {
            try {
                com.dadp.common.sync.config.EndpointStorage.EndpointData endpointData = null;
                
                // endpointSyncService가 있으면 사용, 없으면 EndpointStorage 직접 사용
                if (endpointSyncService != null) {
                    endpointData = endpointSyncService.loadStoredEndpoints();
                    if (endpointData == null) {
                        // Hub에서 다시 조회 시도
                        boolean synced = endpointSyncService.syncEndpointsFromHub();
                        if (synced) {
                            endpointData = endpointSyncService.loadStoredEndpoints();
                        }
                    }
                } else {
                    // endpointSyncService가 null이면 오케스트레이터에서 가져온 EndpointStorage 사용
                    com.dadp.common.sync.config.EndpointStorage storage = this.orchestrator.getEndpointStorage();
                    endpointData = storage.loadEndpoints();
                }
                
                if (endpointData != null && endpointData.getCryptoUrl() != null && !endpointData.getCryptoUrl().trim().isEmpty()) {
                    this.directCryptoAdapter = new DirectCryptoAdapter(config.isFailOpen());
                    this.directCryptoAdapter.setEndpointData(endpointData);
                    log.info("✅ 직접 암복호화 어댑터 지연 초기화 완료: cryptoUrl={}", endpointData.getCryptoUrl());
                }
            } catch (Exception e) {
                log.warn("⚠️ 직접 암복호화 어댑터 지연 초기화 실패 (무시): {}", e.getMessage());
            }
        }
        return directCryptoAdapter;
    }
    
    public HubNotificationService getNotificationService() {
        return notificationService;
    }
    
    public ProxyConfig getConfig() {
        return config;
    }
    
    @Override
    public Statement createStatement() throws SQLException {
        ensureMappingsLoaded();
        Statement actualStmt = actualConnection.createStatement();
        return new DadpProxyStatement(actualStmt, this);
    }
    
    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        log.debug("🔍 PreparedStatement 생성: {}", sql);
        // 정책 매핑 로드 완료 대기 (첫 번째 쿼리 실행 전 정책 적용 보장)
        ensureMappingsLoaded();
        PreparedStatement actualPs = actualConnection.prepareStatement(sql);
        return new DadpProxyPreparedStatement(actualPs, sql, this);
    }
    
    /**
     * 정책 매핑 로드가 완료되었는지 확인하고, 필요시 대기
     * 첫 번째 쿼리 실행 전 정책이 적용되도록 보장합니다.
     */
    private void ensureMappingsLoaded() {
        // 오케스트레이터에서 이미 처리됨
        // 정책 매핑은 오케스트레이터에서 초기화 시점에 로드되므로 대기 불필요
    }
    
    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        return actualConnection.prepareCall(sql);
    }
    
    @Override
    public String nativeSQL(String sql) throws SQLException {
        return actualConnection.nativeSQL(sql);
    }
    
    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        actualConnection.setAutoCommit(autoCommit);
    }
    
    @Override
    public boolean getAutoCommit() throws SQLException {
        return actualConnection.getAutoCommit();
    }
    
    @Override
    public void commit() throws SQLException {
        actualConnection.commit();
    }
    
    @Override
    public void rollback() throws SQLException {
        actualConnection.rollback();
    }
    
    @Override
    public void close() throws SQLException {
        if (!closed) {
            actualConnection.close();
            closed = true;
            // TRACE 레벨로 변경: 연결 풀에서 여러 Connection이 종료될 때 로그 스팸 방지
            log.trace("✅ DADP Proxy Connection 종료");
        }
    }
    
    @Override
    public boolean isClosed() throws SQLException {
        return closed || actualConnection.isClosed();
    }
    
    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return actualConnection.getMetaData();
    }
    
    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        actualConnection.setReadOnly(readOnly);
    }
    
    @Override
    public boolean isReadOnly() throws SQLException {
        return actualConnection.isReadOnly();
    }
    
    @Override
    public void setCatalog(String catalog) throws SQLException {
        actualConnection.setCatalog(catalog);
    }
    
    @Override
    public String getCatalog() throws SQLException {
        return actualConnection.getCatalog();
    }
    
    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        actualConnection.setTransactionIsolation(level);
    }
    
    @Override
    public int getTransactionIsolation() throws SQLException {
        return actualConnection.getTransactionIsolation();
    }
    
    @Override
    public SQLWarning getWarnings() throws SQLException {
        return actualConnection.getWarnings();
    }
    
    @Override
    public void clearWarnings() throws SQLException {
        actualConnection.clearWarnings();
    }
    
    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        ensureMappingsLoaded();
        Statement actualStmt = actualConnection.createStatement(resultSetType, resultSetConcurrency);
        return new DadpProxyStatement(actualStmt, this);
    }
    
    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        ensureMappingsLoaded();
        PreparedStatement actualPs = actualConnection.prepareStatement(sql, resultSetType, resultSetConcurrency);
        return new DadpProxyPreparedStatement(actualPs, sql, this);
    }
    
    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return actualConnection.prepareCall(sql, resultSetType, resultSetConcurrency);
    }
    
    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return actualConnection.getTypeMap();
    }
    
    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        actualConnection.setTypeMap(map);
    }
    
    @Override
    public void setHoldability(int holdability) throws SQLException {
        actualConnection.setHoldability(holdability);
    }
    
    @Override
    public int getHoldability() throws SQLException {
        return actualConnection.getHoldability();
    }
    
    @Override
    public Savepoint setSavepoint() throws SQLException {
        return actualConnection.setSavepoint();
    }
    
    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return actualConnection.setSavepoint(name);
    }
    
    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        actualConnection.rollback(savepoint);
    }
    
    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        actualConnection.releaseSavepoint(savepoint);
    }
    
    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        ensureMappingsLoaded();
        Statement actualStmt = actualConnection.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
        return new DadpProxyStatement(actualStmt, this);
    }
    
    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        ensureMappingsLoaded();
        PreparedStatement actualPs = actualConnection.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        return new DadpProxyPreparedStatement(actualPs, sql, this);
    }
    
    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return actualConnection.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }
    
    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        ensureMappingsLoaded();
        PreparedStatement actualPs = actualConnection.prepareStatement(sql, autoGeneratedKeys);
        return new DadpProxyPreparedStatement(actualPs, sql, this);
    }
    
    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        ensureMappingsLoaded();
        PreparedStatement actualPs = actualConnection.prepareStatement(sql, columnIndexes);
        return new DadpProxyPreparedStatement(actualPs, sql, this);
    }
    
    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        ensureMappingsLoaded();
        PreparedStatement actualPs = actualConnection.prepareStatement(sql, columnNames);
        return new DadpProxyPreparedStatement(actualPs, sql, this);
    }
    
    @Override
    public Clob createClob() throws SQLException {
        return actualConnection.createClob();
    }
    
    @Override
    public Blob createBlob() throws SQLException {
        return actualConnection.createBlob();
    }
    
    @Override
    public NClob createNClob() throws SQLException {
        return actualConnection.createNClob();
    }
    
    @Override
    public SQLXML createSQLXML() throws SQLException {
        return actualConnection.createSQLXML();
    }
    
    @Override
    public boolean isValid(int timeout) throws SQLException {
        return actualConnection.isValid(timeout);
    }
    
    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        actualConnection.setClientInfo(name, value);
    }
    
    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        actualConnection.setClientInfo(properties);
    }
    
    @Override
    public String getClientInfo(String name) throws SQLException {
        return actualConnection.getClientInfo(name);
    }
    
    @Override
    public Properties getClientInfo() throws SQLException {
        return actualConnection.getClientInfo();
    }
    
    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return actualConnection.createArrayOf(typeName, elements);
    }
    
    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return actualConnection.createStruct(typeName, attributes);
    }
    
    @Override
    public void setSchema(String schema) throws SQLException {
        actualConnection.setSchema(schema);
    }
    
    @Override
    public String getSchema() throws SQLException {
        return actualConnection.getSchema();
    }
    
    @Override
    public void abort(Executor executor) throws SQLException {
        actualConnection.abort(executor);
    }
    
    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        actualConnection.setNetworkTimeout(executor, milliseconds);
    }
    
    @Override
    public int getNetworkTimeout() throws SQLException {
        return actualConnection.getNetworkTimeout();
    }
    
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return actualConnection.unwrap(iface);
    }
    
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || actualConnection.isWrapperFor(iface);
    }
    
    // 내부 메서드: 실제 Connection 반환
    Connection getActualConnection() {
        return actualConnection;
    }

    /**
     * SQL 실행 이벤트를 통계 앱으로 전송 (Best-effort).
     */
    void sendSqlTelemetry(String sql, String sqlType, long durationMs, boolean errorFlag) {
        if (telemetryStatsSender != null) {
            telemetryStatsSender.sendSqlEvent(sql, sqlType, durationMs, errorFlag);
        }
    }
}

