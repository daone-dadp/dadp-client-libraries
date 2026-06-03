package com.dadp.jdbc.sync;

import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.WrapperRuntimeConfigManager;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.config.InstanceIdProvider;
import com.dadp.common.sync.config.StoragePathResolver;
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
 * Coordinates the JDBC wrapper bootstrap flow for one wrapper instance.
 *
 * <p>This class loads persisted runtime state and initializes follow-up
 * synchronization services. DB schema collection is owned by the CLI/collector
 * flow in DADP 6.</p>
 */

public class JdbcBootstrapOrchestrator {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(JdbcBootstrapOrchestrator.class);
    
    
    private static final ConcurrentHashMap<String, AtomicBoolean> instanceStartedMap = new ConcurrentHashMap<>();
    
    
    private static final ConcurrentHashMap<String, JdbcBootstrapOrchestrator> orchestratorByInstanceId = new ConcurrentHashMap<>();
    
    
    private final AtomicBoolean started = new AtomicBoolean(false);
    
    
    private final PolicyResolver policyResolver;
    private MappingSyncService mappingSyncService; 
    private EndpointSyncService endpointSyncService; 
    private final EndpointStorage endpointStorage;
    private final InstanceConfigStorage configStorage;
    private final SchemaStorage schemaStorage;
    private DirectCryptoAdapter directCryptoAdapter;
    private final WrapperRuntimeConfigManager tenantIdManager; 
    private final InstanceIdProvider instanceIdProvider; 
    
    
    private JdbcSchemaSyncService schemaSyncService;
    private JdbcSchemaCollector schemaCollector;
    private final ProxyConfig config;
    private final String originalUrl;
    
    
    private volatile String storedDbVendor;
    private volatile String storedHost;
    private volatile int storedPort;
    private volatile String storedDatabase;
    private volatile String storedSchema;
    
    
    private JdbcPolicyMappingSyncService policyMappingSyncService;
    
    
    private volatile HubNotificationService notificationService;

    
    private volatile String nativeJdbcUrl;
    private volatile java.util.Properties nativeJdbcProperties;
    
    private volatile java.util.Properties originalConnectionProperties;

    
    private volatile boolean initialized = false;
    
    
    
    public JdbcBootstrapOrchestrator(String originalUrl, ProxyConfig config) {
        this.originalUrl = originalUrl;
        this.config = config;
        
        
        String instanceId = config.getAlias();
        this.instanceIdProvider = new InstanceIdProvider(java.util.Collections.singletonMap("alias", instanceId));
        
        
        this.configStorage = new InstanceConfigStorage(
            StoragePathResolver.resolveStorageDir(instanceId),
            "proxy-config.json"
        );
        
        
        this.schemaStorage = new SchemaStorage(instanceId);
        this.tenantIdManager = new WrapperRuntimeConfigManager(
            configStorage,
            config.getHubUrl(),
            instanceIdProvider,
            (oldTenantId, newTenantId) -> {
                
                if (newTenantId != null && !newTenantId.equals(oldTenantId)) {
                    log.debug("tenantId changed: {} -> {}, recreating MappingSyncService", oldTenantId, newTenantId);
                    initializeServicesWithTenantId(newTenantId);
                }
            }
        );
        
        
        this.policyResolver = PolicyResolver.getInstance(instanceId);
        
        
        this.endpointStorage = new EndpointStorage(instanceId);
        
        
        this.schemaCollector = new JdbcSchemaCollector(null, config);
        
        
        this.schemaSyncService = new JdbcSchemaSyncService(
            config.getHubUrl(),
            schemaCollector,
            "/hub/api/v1/runtime/wrappers",
            config,
            policyResolver,
            tenantIdManager,
            5,      
            3000,   
            2000    
        );
        
        
    }
    
    
    public static JdbcBootstrapOrchestrator getOrCreate(String instanceId, String originalUrl, ProxyConfig config) {
        return orchestratorByInstanceId.computeIfAbsent(instanceId, k -> new JdbcBootstrapOrchestrator(originalUrl, config));
    }
    
    
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

            
            if ((storedDatabase == null || storedDatabase.trim().isEmpty()) && "oracle".equals(storedDbVendor)) {
                storedDatabase = extractDatabaseFromOracleUrl(originalUrl);
                if (storedDatabase == null || storedDatabase.trim().isEmpty()) {
                    storedDatabase = storedSchema; 
                }
                log.debug("Oracle database fallback value set: {}", storedDatabase);
            }

            
            try {
                this.nativeJdbcUrl = metaData.getURL();
                
                
                if (originalConnectionProperties != null) {
                    this.nativeJdbcProperties = (java.util.Properties) originalConnectionProperties.clone();
                } else {
                    this.nativeJdbcProperties = new java.util.Properties();
                    String userName = metaData.getUserName();
                    if (userName != null) {
                        
                        int atIdx = userName.indexOf('@');
                        if (atIdx > 0) {
                            userName = userName.substring(0, atIdx);
                        }
                        this.nativeJdbcProperties.setProperty("user", userName);
                    }
                }
                log.debug("Native JDBC URL stored for schema reload: url={}, user={}",
                        nativeJdbcUrl, nativeJdbcProperties.getProperty("user"));
            } catch (Exception urlEx) {
                log.debug("Failed to store native JDBC URL (ignored): {}", urlEx.getMessage());
            }
        } catch (Exception e) {
            log.debug("Metadata extraction failed (ignored): {}", e.getMessage());
        }
    }
    
    
    public void setNativeConnectionProperties(java.util.Properties props) {
        if (this.originalConnectionProperties == null && props != null) {
            this.originalConnectionProperties = props;
            
            if (this.nativeJdbcProperties != null && props.getProperty("password") != null) {
                this.nativeJdbcProperties.setProperty("password", props.getProperty("password"));
            }
        }
    }

    
    public String getStoredDbVendor() { return storedDbVendor; }
    public String getStoredHost() { return storedHost; }
    public int getStoredPort() { return storedPort; }
    public String getStoredDatabase() { return storedDatabase; }
    public String getStoredSchema() { return storedSchema; }
    public String getStoredOriginalUrl() { return originalUrl; }
    public boolean hasStoredMetadata() { return storedDbVendor != null && storedHost != null && storedDatabase != null; }
    
    
    public boolean runBootstrapFlow(Connection connection) {
        
        String instanceId = instanceIdProvider.getInstanceId();
        AtomicBoolean instanceStarted = instanceStartedMap.computeIfAbsent(instanceId, k -> new AtomicBoolean(false));
        
        if (!instanceStarted.compareAndSet(false, true)) {
            log.trace("JdbcBootstrapOrchestrator already executed (alias={})", instanceId);
            
            String loadedTenantId = tenantIdManager.loadFromStorage();
            if (loadedTenantId != null && !loadedTenantId.trim().isEmpty()) {
                loadOtherDataFromPersistentStorage();
                initializeServicesWithTenantId(loadedTenantId);
                initializePolicyMappingSyncService(loadedTenantId);
                this.initialized = true;
                if (policyMappingSyncService != null) {
                    policyMappingSyncService.setInitialized(true, loadedTenantId);
                }
                log.info("JDBC Wrapper bootstrap flow completed from runtime enrollment: tenantId={}, alias={}",
                        loadedTenantId, instanceId);
                return true;
            }
            
            log.warn("DADP 6.0 wrapper enrollment is still missing. Runtime remains in passthrough mode until schema-register/manual refresh is completed.");
            return false;
        }
        
        
        if (!started.compareAndSet(false, true)) {
            log.trace("This instance has already been executed.");
            return initialized;
        }
        
        try {
            
            String hubUrl = config.getHubUrl();
            if (hubUrl == null || hubUrl.trim().isEmpty()) {
                log.debug("Hub URL not configured, skipping bootstrap flow.");
                return false;
            }
            
            log.info("JDBC Wrapper bootstrap flow orchestrator starting");
            
            
            storeMetadataFrom(connection);
            
            
            log.info("Step 1: Runtime storage load (DB schema collection is disabled in wrapper 6.0 runtime)");
            
            
            log.info("Step 2: Loading data from persistent storage");
            String tenantId = tenantIdManager.loadFromStorage();
            loadOtherDataFromPersistentStorage();
            
            
            
            {
                String storageDir = StoragePathResolver.resolveStorageDir(instanceId);
                String exportedTenantId = ExportedConfigLoader.loadIfExists(
                    storageDir,
                    instanceId,
                    tenantIdManager,
                    policyResolver,
                    endpointStorage,
                    config
                );
                if (exportedTenantId != null) {
                    tenantId = tenantIdManager.getCachedTenantId();
                    log.info("Step 2.5: Applied exported config: tenantId={}, alias={}",
                            tenantId, instanceId);
                }
            }

            
            log.info("Step 3: Hub 6 runtime enrollment validation");
            boolean runtimeEnrollmentAvailable = false;

            if (tenantIdManager.hasRuntimeEnrollment()) {
                tenantId = tenantIdManager.getCachedTenantId();
                runtimeEnrollmentAvailable = true;
                log.info("Runtime enrollment loaded: tenantId={}, alias={}", tenantId, instanceId);
            } else {
                log.warn("DADP 6.0 wrapper enrollment is missing. Run CLI schema-register and manual refresh before wrapper runtime sync.");
            }
            
            
            if (tenantId == null || tenantId.trim().isEmpty()) {
                log.warn("Cannot initialize services without tenantId.");
                initialized = false;
                return false;
            }
            
            
            log.info("Step 4: Service initialization (crypto service initialized regardless of Hub registration result)");
            initializeServicesWithTenantId(tenantId);
            
            
            if (runtimeEnrollmentAvailable) {
                log.info("Step 5: Policy mapping sync service initialization");
                initializePolicyMappingSyncService(tenantId);
                
                
                initialized = true;
                if (policyMappingSyncService != null) {
                    policyMappingSyncService.setInitialized(true, tenantId);
                }
                log.info("JDBC Wrapper bootstrap flow completed: tenantId={}, alias={}",
                        tenantIdManager.getCachedTenantId(), instanceId);
            } else {
                
                
                log.warn("Runtime enrollment unavailable: crypto service initialized but policy mapping sync not started.");
                initialized = true; 
                log.info("JDBC Wrapper bootstrap flow completed (limited): tenantId={}, alias={}, crypto available",
                        tenantIdManager.getCachedTenantId(), instanceId);
            }
            return true;
            
        } catch (Exception e) {
            
            
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.trim().isEmpty()) {
                errorMessage = e.getClass().getSimpleName();
            }
            log.warn("Bootstrap flow failed: {}", errorMessage);
            return false;
        }
    }
    
    
    private void loadOtherDataFromPersistentStorage() {
        
        policyResolver.reloadFromStorage();
        Long loadedPolicyVersion = policyResolver.getCurrentVersion();
        if (loadedPolicyVersion != null) {
            log.debug("Policy mappings loaded from persistent storage: version={}", loadedPolicyVersion);
        }
        PolicyResolver.StoredLogConfig storedLogConfig = policyResolver.getStoredLogConfig();
        if (storedLogConfig != null && storedLogConfig.getEnabled() != null) {
            DadpLoggerFactory.setFromHub(storedLogConfig.getEnabled(), storedLogConfig.getLevel());
            log.info("Stored log config restored from persistent storage: enabled={}, level={}",
                    storedLogConfig.getEnabled(), storedLogConfig.getLevel());
        }
        
        
        EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
        if (endpointData != null) {
            log.debug("Endpoint info loaded from persistent storage: cryptoUrl={}, tenantId={}, version={}",
                    endpointData.getCryptoUrl(), endpointData.getTenantId(), endpointData.getVersion());
        }
        
        
        List<SchemaMetadata> storedSchemas = schemaStorage.loadSchemas();
        if (!storedSchemas.isEmpty()) {
            log.debug("Schemas loaded from persistent storage: {} schemas", storedSchemas.size());
        }
        
        
        log.debug("Wrapper runtime identity loaded from storage: tenantId only; alias is JDBC URL-only.");
    }
    
    private void saveSchemasToStorage(List<SchemaMetadata> currentSchemas) {
        if (currentSchemas == null || currentSchemas.isEmpty()) {
            log.debug("No collected schemas to save.");
            return;
        }
        try {
            for (SchemaMetadata schema : currentSchemas) {
                if (schema != null) {
                    schema.setPolicyName(null);
                }
            }
            int updatedCount = schemaStorage.compareAndUpdateSchemas(currentSchemas);
            log.info("Schemas saved to persistent storage and status updated: {} schemas updated", updatedCount);
        } catch (Exception e) {
            log.warn("Schema save failed: {}", e.getMessage());
        }
    }
    
    private void recreateSchemaRuntimeServices() {
        log.debug("Runtime schema services are disabled in wrapper 6.0 runtime.");
    }
    private void initializeServicesWithTenantId(String tenantId) {
        
        
        String instanceId = instanceIdProvider.getInstanceId();
        this.mappingSyncService = new MappingSyncService(
            config.getHubUrl(),
            tenantId,
            instanceId,
            "/hub/api/v1/runtime/wrappers",
            policyResolver
        );
        
        
        String endpointStorageDir = StoragePathResolver.resolveStorageDir(instanceId);
        String endpointFileName = "crypto-endpoints.json";
        this.endpointSyncService = new EndpointSyncService(
            config.getHubUrl(),
            tenantId,
            instanceId,
            endpointStorageDir,
            endpointFileName
        );
        
        
        
        
        this.directCryptoAdapter = new DirectCryptoAdapter(tenantIdManager.isFailOpen());
        applyCryptoMode(this.directCryptoAdapter);
        
        
        EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
        if (endpointData != null && endpointData.getCryptoUrl() != null && 
            !endpointData.getCryptoUrl().trim().isEmpty()) {
            directCryptoAdapter.setEndpointData(endpointData);
            log.info("Crypto adapter initialized: cryptoUrl={}, tenantId={}, version={}",
                    endpointData.getCryptoUrl(), endpointData.getTenantId(), endpointData.getVersion());
        }
        
        
        if (this.notificationService == null) {
            try {
                this.notificationService = new HubNotificationService(
                    config.getHubUrl(),
                    tenantId,
                    instanceId,
                    config.isEnableLogging()
                );
                log.debug("Hub notification service initialized (shared): tenantId={}", tenantId);
            } catch (Exception e) {
                log.warn("Hub notification service initialization failed (ignored): {}", e.getMessage());
                this.notificationService = null;
            }
        }
    }

    private void applyCryptoMode(DirectCryptoAdapter adapter) {
        if (adapter != null) {
            adapter.setFailOpen(tenantIdManager.isFailOpen());
            adapter.setCryptoMode(
                    tenantIdManager.getCryptoMode(),
                    config.getHubUrl(),
                    config.isCryptoLocalFallbackRemote(),
                    config.getCryptoLocalTimeoutMs(),
                    tenantIdManager.getCachedTenantId(),
                    config.isWrapperCryptoStatsEnabled(),
                    config.getWrapperCryptoStatsAggregationLevel());
        }
    }

    private void initializePolicyMappingSyncService(String tenantId) {
        try {
            
            
            this.policyMappingSyncService = new JdbcPolicyMappingSyncService(
                mappingSyncService,
                endpointSyncService,
                schemaSyncService,
                policyResolver,
                directCryptoAdapter,
                endpointStorage,
                config,
                configStorage,
                schemaStorage
            );
            
            
            log.info("JdbcPolicyMappingSyncService initialized: tenantId={}", tenantId);
        } catch (Exception e) {
            log.warn("JdbcPolicyMappingSyncService initialization failed: {}", e.getMessage());
        }
    }
    
    
    private String extractHostFromUrl(String url, String dbVendor) {
        try {
            
            String baseUrl = url;
            int queryIdx = url.indexOf('?');
            if (queryIdx > 0) {
                baseUrl = url.substring(0, queryIdx);
            }

            if ("oracle".equals(dbVendor)) {
                
                int atIdx = baseUrl.indexOf('@');
                if (atIdx >= 0) {
                    String afterAt = baseUrl.substring(atIdx + 1);
                    
                    if (afterAt.startsWith("//")) {
                        afterAt = afterAt.substring(2);
                    }
                    
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

    
    private int extractPortFromUrl(String url, String dbVendor) {
        try {
            
            String baseUrl = url;
            int queryIdx = url.indexOf('?');
            if (queryIdx > 0) {
                baseUrl = url.substring(0, queryIdx);
            }

            if ("oracle".equals(dbVendor)) {
                
                int atIdx = baseUrl.indexOf('@');
                if (atIdx >= 0) {
                    String afterAt = baseUrl.substring(atIdx + 1);
                    if (afterAt.startsWith("//")) {
                        afterAt = afterAt.substring(2);
                    }
                    
                    int colonIdx = afterAt.indexOf(':');
                    if (colonIdx >= 0) {
                        String afterColon = afterAt.substring(colonIdx + 1);
                        
                        int endIdx = afterColon.indexOf('/');
                        int endIdx2 = afterColon.indexOf(':');
                        if (endIdx < 0) endIdx = afterColon.length();
                        if (endIdx2 >= 0 && endIdx2 < endIdx) endIdx = endIdx2;
                        return Integer.parseInt(afterColon.substring(0, endIdx));
                    }
                }
                return 1521; 
            }

            
            int start = baseUrl.indexOf("://") + 3;
            if (start < 3) {
                return getDefaultPort(dbVendor);
            }
            int colonIndex = baseUrl.indexOf(":", start);
            if (colonIndex < 0) {
                return getDefaultPort(dbVendor);
            }
            String afterColon = baseUrl.substring(colonIndex + 1);
            
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

    
    private int getDefaultPort(String dbVendor) {
        if (dbVendor == null) return 3306;
        switch (dbVendor) {
            case "oracle": return 1521;
            case "postgresql": return 5432;
            case "mssql": return 1433;
            case "sqream": return 3108;
            default: return 3306;
        }
    }

    
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

            
            if (afterAt.startsWith("//")) {
                int lastSlash = afterAt.lastIndexOf('/');
                if (lastSlash > 1) {
                    return afterAt.substring(lastSlash + 1);
                }
            }

            
            int lastColon = afterAt.lastIndexOf(':');
            if (lastColon > 0) {
                String candidate = afterAt.substring(lastColon + 1);
                
                try {
                    Integer.parseInt(candidate);
                    return null; 
                } catch (NumberFormatException e) {
                    return candidate; 
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    
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
        } else if (lower.contains("sqream")) {
            return "sqream";
        }
        return lower; 
    }
    
    
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
        } else if (lower.contains("sqream")) {
            return connection.getCatalog();
        }
        return connection.getCatalog();
    }
    
    
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
    
    public String getCachedTenantId() {
        
        return tenantIdManager.getCachedTenantId();
    }
    
    public String getRuntimeCryptoMode() {
        return tenantIdManager.getCryptoMode();
    }

    public boolean isRuntimeFailOpen() {
        return tenantIdManager.isFailOpen();
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
    
    
    public HubNotificationService getNotificationService() {
        return notificationService;
    }

    
    public void forceReloadSchemas() {
        log.warn("Schema force reload is disabled in wrapper 6.0 runtime. Use the collector/CLI schema-register flow.");
    }
}
