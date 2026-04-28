package com.dadp.jdbc.sync;

import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.HubIdManager;
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
 * Coordinates the JDBC wrapper bootstrap flow for one wrapper instance.
 *
 * <p>This class loads persisted state, gathers schema metadata, registers the
 * datasource with Hub, and initializes follow-up synchronization services.</p>
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
    private final HubIdManager hubIdManager; 
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
    private volatile String cachedDatasourceId = null;
    
    
    
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
        this.hubIdManager = new HubIdManager(
            configStorage,
            config.getHubUrl(),
            instanceIdProvider,
            (oldHubId, newHubId) -> {
                
                if (newHubId != null && !newHubId.equals(oldHubId)) {
                    log.debug("hubId changed: {} -> {}, recreating MappingSyncService", oldHubId, newHubId);
                    initializeServicesWithHubId(newHubId);
                }
            }
        );
        
        
        this.policyResolver = PolicyResolver.getInstance(instanceId);
        
        
        this.endpointStorage = new EndpointStorage(instanceId);
        
        
        this.schemaCollector = new JdbcSchemaCollector(null, config);
        
        
        this.schemaSyncService = new JdbcSchemaSyncService(
            config.getHubUrl(),
            schemaCollector,
            "/hub/api/v1/proxy",  
            config,
            policyResolver,
            hubIdManager,
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
            
            String loadedHubId = hubIdManager.loadFromStorage();
            if (loadedHubId != null && !loadedHubId.trim().isEmpty()) {
                this.initialized = true;
                
                if (hasStoredMetadata()) {
                    try {
                        String cached = com.dadp.jdbc.config.DatasourceStorage.loadDatasourceId(
                            instanceId, storedDbVendor, storedHost, storedPort, storedDatabase, storedSchema);
                        if (cached != null && !cached.trim().isEmpty()) {
                            this.cachedDatasourceId = cached;
                        }
                    } catch (Exception e) {
                        log.debug("datasourceId load failed (ignored): {}", e.getMessage());
                    }
                }
                return true;
            }
            
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
            
            
            log.info("Step 1: DB schema collection (one-time)");
            List<SchemaMetadata> currentSchemas = schemaSyncService.collectSchemasWithRetry(connection, 5, 2000);
            if (currentSchemas == null || currentSchemas.isEmpty()) {
                log.warn("Schema collection failed or returned 0 (continuing in fail-open mode)");
            } else {
                log.info("Schema collection completed: {} schemas", currentSchemas.size());
            }
            
            
            log.info("Step 2: Loading data from persistent storage");
            String hubId = hubIdManager.loadFromStorage();
            loadOtherDataFromPersistentStorage();
            
            
            
            {
                String storageDir = StoragePathResolver.resolveStorageDir(instanceId);
                String exportedDatasourceId = ExportedConfigLoader.loadIfExists(
                    storageDir,
                    instanceId,
                    hubIdManager,
                    policyResolver,
                    endpointStorage,
                    config
                );
                if (exportedDatasourceId != null) {
                    hubId = hubIdManager.getCachedHubId();
                    this.cachedDatasourceId = exportedDatasourceId;
                    log.info("Step 2.5: Applied exported config: hubId={}, datasourceId={}",
                            hubId, exportedDatasourceId);
                }
            }

            
            if (currentSchemas != null && !currentSchemas.isEmpty()) {
                saveSchemasToStorage(currentSchemas);
            }

            
            log.info("Step 3: Hub registration and schema registration");
            boolean schemaRegistrationCompleted = false;

            if (hubId == null) {
                
                schemaRegistrationCompleted = registerWithHub();
                
                hubId = hubIdManager.getCachedHubId();
            } else {
                
                
                String oldHubId = hubId;
                schemaRegistrationCompleted = ensureSchemasSyncedToHub(hubId);
                
                String newHubId = hubIdManager.getCachedHubId();
                if (newHubId != null && !newHubId.equals(oldHubId)) {
                    log.info("hubId changed due to re-registration: {} -> {}", oldHubId, newHubId);
                    hubId = newHubId;
                }
            }
            
            
            if (hubId == null || hubId.trim().isEmpty()) {
                log.warn("Cannot initialize services without hubId.");
                initialized = false;
                return false;
            }
            
            
            hubIdManager.setHubId(hubId, true);
            
            
            
            log.info("Step 4: Service initialization (crypto service initialized regardless of Hub registration result)");
            initializeServicesWithHubId(hubId);
            
            
            if (schemaRegistrationCompleted) {
                log.info("Step 5: Policy mapping sync service initialization");
                initializePolicyMappingSyncService(hubId);
                
                
                initialized = true;
                if (policyMappingSyncService != null) {
                    policyMappingSyncService.setInitialized(true, hubId);
                }
                log.info("JDBC Wrapper bootstrap flow completed: hubId={}, datasourceId={}", hubIdManager.getCachedHubId(), cachedDatasourceId);
            } else {
                
                
                log.warn("Hub registration failed: crypto service initialized but policy mapping sync not started. Will retry when Hub connection is restored.");
                initialized = true; 
                log.info("JDBC Wrapper bootstrap flow completed (limited): hubId={}, datasourceId={}, crypto available",
                        hubIdManager.getCachedHubId(), cachedDatasourceId);
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
            log.debug("Endpoint info loaded from persistent storage: cryptoUrl={}, hubId={}, version={}",
                    endpointData.getCryptoUrl(), endpointData.getHubId(), endpointData.getVersion());
        }
        
        
        List<SchemaMetadata> storedSchemas = schemaStorage.loadSchemas();
        if (!storedSchemas.isEmpty()) {
            log.debug("Schemas loaded from persistent storage: {} schemas", storedSchemas.size());
        }
        
        
        if (hasStoredMetadata()) {
            try {
                String cached = com.dadp.jdbc.config.DatasourceStorage.loadDatasourceId(
                    instanceIdProvider.getInstanceId(), storedDbVendor, storedHost, storedPort, storedDatabase, storedSchema);
                if (cached != null && !cached.trim().isEmpty()) {
                    this.cachedDatasourceId = cached;
                    log.debug("Stored datasourceId loaded: datasourceId={}", this.cachedDatasourceId);
                }
            } catch (Exception e) {
                log.warn("Failed to load datasourceId: {}", e.getMessage());
            }
        }
    }
    
    
    private boolean registerWithHub() {
        String instanceId = instanceIdProvider.getInstanceId();
        
        
        log.info("Hub Datasource registration starting: alias={}", instanceId);
        DatasourceRegistrationService.DatasourceInfo datasourceInfo = registerDatasource(null);
        if (datasourceInfo == null) {
            log.warn("Datasource registration failed: Hub unreachable or response error");
            return false;
        }
        
        
        String hubId = datasourceInfo.getHubId();
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("Datasource registration response missing hubId");
            return false;
        }
        
        
        hubIdManager.setHubId(hubId, true);
        String wrapperHubId = datasourceInfo.getWrapperHubId();
        if (wrapperHubId == null || wrapperHubId.trim().isEmpty()) {
            wrapperHubId = hubId;
        }
        if (datasourceInfo.getWrapperAuthSecret() != null && !datasourceInfo.getWrapperAuthSecret().trim().isEmpty()) {
            hubIdManager.setWrapperAuthSecret(wrapperHubId, datasourceInfo.getWrapperAuthSecret(), true);
            applyCryptoMode(this.directCryptoAdapter);
        }
        log.info("Hub Datasource registration completed: hubId={}, datasourceId={}", hubId, datasourceInfo.getDatasourceId());
        
        
        String endpointStorageDir = StoragePathResolver.resolveStorageDir(instanceId);
        String endpointFileName = "crypto-endpoints.json";
        this.endpointSyncService = new EndpointSyncService(
            config.getHubUrl(),
            hubId,
            instanceId,
            endpointStorageDir,
            endpointFileName
        );
        
        
        if (cachedDatasourceId != null && !cachedDatasourceId.trim().isEmpty()) {
            this.schemaCollector = new JdbcSchemaCollector(cachedDatasourceId, config);
            this.schemaSyncService = new JdbcSchemaSyncService(
                config.getHubUrl(),
                schemaCollector,
                "/hub/api/v1/proxy",  
                config,
                policyResolver,
                hubIdManager,    
                5,      
                3000,   
                2000    
            );
            log.debug("schemaCollector recreated after datasourceId set: datasourceId={}", cachedDatasourceId);
        }
        
        
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
        
        
        if (schemaSyncService == null) {
            log.warn("JdbcSchemaSyncService unavailable, cannot perform schema sync.");
            return false;
        }
        
        List<SchemaMetadata> createdSchemas = schemaStorage.getCreatedSchemas();
        if (!createdSchemas.isEmpty()) {
            log.info("Step 3: Sending CREATED schemas to Hub: hubId={}, schemaCount={}", hubId, createdSchemas.size());
            boolean synced = syncCreatedSchemasToHub(hubId, createdSchemas);
            if (synced) {
                
                List<String> schemaKeys = new java.util.ArrayList<>();
                for (SchemaMetadata schema : createdSchemas) {
                    if (schema != null) {
                        schemaKeys.add(schema.getKey());
                    }
                }
                int updatedCount = schemaStorage.updateSchemasStatus(schemaKeys, SchemaMetadata.Status.REGISTERED);
                log.info("CREATED schemas sent and status updated: {} schemas (CREATED -> REGISTERED)", updatedCount);
                log.info("Hub registration completed: hubId={}", hubId);
                return true;  
            } else {
                log.warn("CREATED schemas send failed (no Hub response)");
                return false;  
            }
        } else {
            log.info("Step 3: No CREATED schemas (only already-registered schemas exist)");
        log.info("Hub registration completed: hubId={}", hubId);
            return true;  
        }
        
        
        
    }
    
    
    private String registerInstance(String hubUrl, String instanceId) {
        
        
        
        
        log.warn("registerInstance() is deprecated. Use registerDatasource() to obtain hubId.");
            return null;
    }
    
    
    private DatasourceRegistrationService.DatasourceInfo registerDatasource(String caCertPath) {
        try {
            
            if (!hasStoredMetadata()) {
                log.warn("No stored metadata: skipping registerDatasource");
                return null;
            }
            String dbVendor = storedDbVendor;
            String host = storedHost;
            int port = storedPort;
            String database = storedDatabase;
            String schema = storedSchema;
            
            
            
            Long currentVersion = policyResolver.getCurrentVersion();
            if (currentVersion == null) {
                currentVersion = 0L;
            }
            
            DatasourceRegistrationService registrationService = 
                new DatasourceRegistrationService(config.getHubUrl(), instanceIdProvider.getInstanceId(), caCertPath);
            DatasourceRegistrationService.DatasourceInfo datasourceInfo = registrationService.registerOrGetDatasource(
                dbVendor, host, port, database, schema, currentVersion, hubIdManager.getCachedHubId()
            );
            
            if (datasourceInfo != null && datasourceInfo.getDatasourceId() != null) {
                log.info("Datasource registration completed: datasourceId={}, displayName={}, hubId={}",
                    datasourceInfo.getDatasourceId(), datasourceInfo.getDisplayName(), datasourceInfo.getHubId());
                
                
                this.cachedDatasourceId = datasourceInfo.getDatasourceId();
                
                return datasourceInfo;
            } else {
                log.warn("Datasource registration failed: Hub unreachable or null response. hubUrl={}, alias={}",
                    config.getHubUrl(), instanceIdProvider.getInstanceId());
                return null;
            }
        } catch (Exception e) {
            log.warn("Datasource registration failed: hubUrl={}, alias={}, error={}",
                config.getHubUrl(), instanceIdProvider.getInstanceId(), e.getMessage());
            return null;
        }
    }
    
    
    private String ensureRootCACertificate(String hubUrl, String instanceId) {
        log.info("Root CA certificate verification starting: hubUrl={}, alias={}", hubUrl, instanceId);
        
        
        String manualCaCertPath = System.getProperty("dadp.ca.cert.path");
        if (manualCaCertPath == null || manualCaCertPath.trim().isEmpty()) {
            manualCaCertPath = System.getenv("DADP_CA_CERT_PATH");
        }
        if (manualCaCertPath != null && !manualCaCertPath.trim().isEmpty()) {
            
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
            
            boolean certExists = java.nio.file.Files.exists(caCertPath);
            
            if (certExists) {
                log.info("Root CA certificate found in storage: path={}", caCertPath);
            } else {
                log.info("Root CA certificate not found in storage (manual config or file placement required): path={}", caCertPath);
                return null;
            }
            
            
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
    
    
    private boolean verifySSLContextCreation(String caCertPath) {
        try {
            
            String pem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(caCertPath)), "UTF-8");
            if (pem == null || pem.trim().isEmpty()) {
                log.warn("SSLContext creation verification failed: certificate file is empty");
                return false;
            }
            
            
            String certContent = pem.replace("-----BEGIN CERTIFICATE-----", "")
                                    .replace("-----END CERTIFICATE-----", "")
                                    .replaceAll("\\s", "");
            byte[] certBytes = java.util.Base64.getDecoder().decode(certContent);
            java.security.cert.CertificateFactory certFactory = 
                java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.X509Certificate caCert = 
                (java.security.cert.X509Certificate) certFactory.generateCertificate(
                    new java.io.ByteArrayInputStream(certBytes));
            
            
            java.security.KeyStore trustStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("dadp-root-ca", caCert);
            
            
            javax.net.ssl.TrustManagerFactory trustManagerFactory = 
                javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            
            
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), new java.security.SecureRandom());
            
            
            return true;
        } catch (Exception e) {
            
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.trim().isEmpty()) {
                errorMessage = e.getClass().getSimpleName();
            }
            log.warn("SSLContext creation verification failed: error={}", errorMessage);
            return false;
        }
    }
    
    
    private boolean validateRootCACertificate(java.nio.file.Path certPath) {
        try {
            
            String pem = new String(java.nio.file.Files.readAllBytes(certPath), "UTF-8");
            if (pem == null || pem.trim().isEmpty()) {
                log.warn("Root CA certificate file is empty");
                return false;
            }
            
            
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
    
    
    private boolean ensureSchemasSyncedToHub(String hubId) {
        
        List<SchemaMetadata> createdSchemas = schemaStorage.getCreatedSchemas();
        if (!createdSchemas.isEmpty()) {
            log.info("Sending CREATED schemas to Hub: hubId={}, schemaCount={}", hubId, createdSchemas.size());
            boolean synced = syncCreatedSchemasToHub(hubId, createdSchemas);
            if (synced) {
                
                List<String> schemaKeys = new java.util.ArrayList<>();
                for (SchemaMetadata schema : createdSchemas) {
                    if (schema != null) {
                        schemaKeys.add(schema.getKey());
                    }
                }
                schemaStorage.updateSchemasStatus(schemaKeys, SchemaMetadata.Status.REGISTERED);
                log.info("CREATED schemas sent and status updated: {} schemas (CREATED -> REGISTERED)",
                        createdSchemas.size());
                return true;  
            } else {
                
                log.info("CREATED schemas send failed (possible 404): hubId={}, attempting re-registration", hubId);
                boolean reRegistered = registerWithHub();
                if (reRegistered) {
                    String newHubId = hubIdManager.getCachedHubId(); 
                    log.info("Re-registration completed: new hubId={}", newHubId);
                    
                    return ensureSchemasSyncedToHub(newHubId);
                } else {
                    log.warn("Re-registration failed");
                    return false;
                }
            }
        } else {
            log.debug("No schemas to send, assumed already synced with Hub");
            return true;  
        }
    }
    
    
    private boolean syncCreatedSchemasToHub(String hubId, List<SchemaMetadata> createdSchemas) {
        if (createdSchemas == null || createdSchemas.isEmpty()) {
            return false;
        }
        
        
        if (cachedDatasourceId != null && !cachedDatasourceId.trim().isEmpty()) {
            for (SchemaMetadata schema : createdSchemas) {
                if (schema != null && (schema.getDatasourceId() == null || schema.getDatasourceId().trim().isEmpty())) {
                    schema.setDatasourceId(cachedDatasourceId);
                    log.trace("Set datasourceId on schema before sending: schema={}.{}.{}, datasourceId={}",
                        schema.getSchemaName(), schema.getTableName(), schema.getColumnName(), cachedDatasourceId);
                }
            }
        }
        
        
        
        boolean success = schemaSyncService.syncSpecificSchemasToHub(createdSchemas);
        
        
        if (!success) {
            
            
            log.info("Schema sync failed (possible 404), re-registration required");
        }
        
        return success;
    }
    
    
    private void initializeServicesWithHubId(String hubId) {
        
        
        String instanceId = instanceIdProvider.getInstanceId();
        this.mappingSyncService = new MappingSyncService(
            config.getHubUrl(),
            hubId,
            instanceId,
            cachedDatasourceId,
            "/hub/api/v1/proxy",  
            policyResolver
        );
        
        
        String endpointStorageDir = StoragePathResolver.resolveStorageDir(instanceId);
        String endpointFileName = "crypto-endpoints.json";
        this.endpointSyncService = new EndpointSyncService(
            config.getHubUrl(),
            hubId,
            instanceId,
            endpointStorageDir,
            endpointFileName
        );
        
        
        
        
        this.directCryptoAdapter = new DirectCryptoAdapter(config.isFailOpen());
        applyCryptoMode(this.directCryptoAdapter);
        
        
        EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
        if (endpointData != null && endpointData.getCryptoUrl() != null && 
            !endpointData.getCryptoUrl().trim().isEmpty()) {
            directCryptoAdapter.setEndpointData(endpointData);
            log.info("Crypto adapter initialized: cryptoUrl={}, hubId={}, version={}",
                    endpointData.getCryptoUrl(), endpointData.getHubId(), endpointData.getVersion());
        }
        
        
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

    private void applyCryptoMode(DirectCryptoAdapter adapter) {
        if (adapter != null) {
            String effectiveHubAuthId = hubIdManager.getCachedHubId();
            String effectiveHubAuthSecret = hubIdManager.getCachedWrapperAuthSecret();
            if (effectiveHubAuthId == null || effectiveHubAuthId.trim().isEmpty()) {
                effectiveHubAuthId = config.getCryptoLocalHubAuthId();
            }
            if (effectiveHubAuthSecret == null || effectiveHubAuthSecret.trim().isEmpty()) {
                effectiveHubAuthSecret = config.getCryptoLocalHubAuthSecret();
            }
            adapter.setCryptoMode(
                    config.getCryptoMode(),
                    config.getHubUrl(),
                    config.isCryptoLocalFallbackRemote(),
                    config.getCryptoLocalTimeoutMs(),
                    effectiveHubAuthId,
                    effectiveHubAuthSecret,
                    config.isWrapperCryptoStatsEnabled(),
                    config.getWrapperCryptoStatsAggregationLevel());
        }
    }

    public String getWrapperAuthSecret() {
        return hubIdManager.getCachedWrapperAuthSecret();
    }
    
    
    private void initializePolicyMappingSyncService(String hubId) {
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
                schemaStorage,
                cachedDatasourceId
            );
            
            
            final JdbcBootstrapOrchestrator self = this;
            policyMappingSyncService.setReregistrationCallback(() -> {
                log.info("Re-registration callback invoked: performing Datasource re-registration");
                
                self.registerWithHub();
            });

            
            policyMappingSyncService.setSchemaReloadCallback(() -> {
                log.info("Schema force reload callback invoked");
                self.forceReloadSchemas();
            });

            log.info("JdbcPolicyMappingSyncService initialized: hubId={}", hubId);
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
    
    public String getCachedHubId() {
        
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
    
    
    public HubNotificationService getNotificationService() {
        return notificationService;
    }

    
    public void forceReloadSchemas() {
        if (nativeJdbcUrl == null || nativeJdbcUrl.trim().isEmpty()) {
            log.warn("Schema force reload failed: native JDBC URL not available");
            return;
        }

        String hubId = hubIdManager.getCachedHubId();
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("Schema force reload failed: hubId not available");
            return;
        }

        log.info("Schema force reload starting: nativeUrl={}, hubId={}", nativeJdbcUrl, hubId);

        Connection connection = null;
        try {
            
            if (nativeJdbcProperties != null && !nativeJdbcProperties.isEmpty()) {
                connection = java.sql.DriverManager.getConnection(nativeJdbcUrl, nativeJdbcProperties);
            } else {
                connection = java.sql.DriverManager.getConnection(nativeJdbcUrl);
            }

            
            List<SchemaMetadata> reloadedSchemas = schemaSyncService.collectSchemasWithRetry(connection, 3, 2000);
            if (reloadedSchemas == null || reloadedSchemas.isEmpty()) {
                log.warn("Schema force reload: no schemas collected");
                return;
            }

            log.info("Schema force reload: collected {} schemas", reloadedSchemas.size());

            
            if (cachedDatasourceId != null) {
                for (SchemaMetadata schema : reloadedSchemas) {
                    if (schema != null && (schema.getDatasourceId() == null || schema.getDatasourceId().trim().isEmpty())) {
                        schema.setDatasourceId(cachedDatasourceId);
                    }
                }
            }

            
            saveSchemasToStorage(reloadedSchemas);

            
            boolean synced = schemaSyncService.syncSpecificSchemasToHub(reloadedSchemas);
            if (synced) {
                log.info("Schema force reload completed: {} schemas sent to Hub", reloadedSchemas.size());
            } else {
                log.warn("Schema force reload: Hub sync failed (will retry on next cycle)");
            }

        } catch (Exception e) {
            log.warn("Schema force reload failed: {}", e.getMessage());
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    log.debug("Failed to close schema reload connection: {}", e.getMessage());
                }
            }
        }
    }
}
