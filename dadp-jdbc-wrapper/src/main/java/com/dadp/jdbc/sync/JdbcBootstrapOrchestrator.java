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
 * JDBC Wrapper ë¶€???Œë¡œ???¤ì??¤íŠ¸?ˆì´??
 * 
 * AOP??AopBootstrapOrchestrator?€ ?™ì¼???¨í„´???°ë¦…?ˆë‹¤.
 * 
 * <h2>?°ì´??ì¶œì²˜ (?¸ì œÂ·?´ë””??ê°’ì„ ê°€?¸ì˜¤?”ì?)</h2>
 * <ul>
 *   <li><b>?êµ¬?€?¥ì†Œ?ì„œ ê°€?¸ì˜¤???œì </b>
 *     <ul>
 *       <li>hubId: {@link HubIdManager#loadFromStorage()} ??InstanceConfigStorage (proxy-config.json)</li>
 *       <li>?•ì±… ë§¤í•‘Â·ë²„ì „: PolicyResolver ??PolicyMappingStorage (policy-mappings.json)</li>
 *       <li>?”ë“œ?¬ì¸?? {@link com.dadp.common.sync.config.EndpointStorage#loadEndpoints()} ??crypto-endpoints.json</li>
 *       <li>?¤í‚¤ë§?ëª©ë¡: SchemaStorage.loadSchemas() ??schemas.json</li>
 *       <li>datasourceId: DatasourceStorage.loadDatasourceId() ??DB ??host,port,db,schema)ë¡?ë¡œì»¬ ?Œì¼ ì¡°íšŒ</li>
 *     </ul>
 *     ??2?¨ê³„ loadOtherDataFromPersistentStorage() ë°??œì´ë¯??¤í–‰?¨â€?ë¶„ê¸°?ì„œ loadFromStorage() ???¸ì¶œ.</li>
 *   <li><b>DBë¡œë????»ì–´?¤ëŠ” ?œì </b>
 *     <ul>
 *       <li>connection.getMetaData(), getCatalog(), getSchema() ??dbVendor, database, schema, host/port ì¶”ì¶œ</li>
 *       <li>schemaCollector.collectSchemas() ??SchemaRecognizerê°€ JDBC Connection?¼ë¡œ ?Œì´ë¸?ì»¬ëŸ¼ ë©”í??°ì´???˜ì§‘</li>
 *     </ul>
 *     ??1?¨ê³„ collectSchemasWithRetry, 2?¨ê³„ loadOtherDataFromPersistentStorage, 3?¨ê³„ saveSchemasToStorage(currentSchemas), registerDatasource() ?´ë?.</li>
 *   <li><b>Hubë¡œë???ë°›ì•„?¤ëŠ” ?œì </b>
 *     <ul>
 *       <li>Datasource ?±ë¡: registerOrGetDatasource() ??hubId, datasourceId ?‘ë‹µ</li>
 *       <li>?¤í‚¤ë§??„ì†¡: syncSpecificSchemasToHub() ??Hubê°€ ?¤í‚¤ë§??€??(Wrapper?’Hub ë°©í–¥)</li>
 *       <li>?•ì±… ë§¤í•‘Â·?”ë“œ?¬ì¸?? JdbcPolicyMappingSyncService ì£¼ê¸° ?™ê¸°?”ì—??Hub APIë¡?ë¡œë“œ ???êµ¬?€?¥ì†Œ???€??/li>
 *     </ul>
 *     ??3?¨ê³„ registerWithHub() ë°?ensureSchemasSyncedToHub(), 5?¨ê³„ ?´í›„ ì£¼ê¸° ?™ê¸°??</li>
 * </ul>
 * 
 * <p><b>?¼ë¦¬ ?œì„œ (ë¶€??</b>: 1) DB ?¤í‚¤ë§?1???˜ì§‘ 2) ?êµ¬?€?¥ì†Œ ë¡œë“œ 3) ?€?¥ì†Œ vs ?˜ì§‘ ?¤í‚¤ë§?ë¹„êµÂ·?€??4) hubId ?†ìœ¼ë©?Hub?ì„œ ?ë“ 5) ?ì„± ?¤í‚¤ë§?Hub ?±ë¡ ??ì´ˆê¸°??ì¢…ë£Œ. ë°˜ë³µ(ë§¤í•‘ ì²´í¬): 304=?™ê¸°???„ë£Œ, 200=?™ê¸°???˜í–‰, 404=?¸ìŠ¤?´ìŠ¤ ?†ìŒ?’ì¬?±ë¡.</p>
 * 
 * @author DADP Development Team
 * @version 5.2.2
 * @since 2026-01-08
 */
public class JdbcBootstrapOrchestrator {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(JdbcBootstrapOrchestrator.class);
    
    // instanceIdë³?1???¤í–‰ ë³´ì¥ (static?¼ë¡œ ?„ì—­ ê´€ë¦?
    private static final ConcurrentHashMap<String, AtomicBoolean> instanceStartedMap = new ConcurrentHashMap<>();
    
    // instanceId???¤ì??¤íŠ¸?ˆì´??1?¸íŠ¸ ê³µìœ  (static ìºì‹œ)
    private static final ConcurrentHashMap<String, JdbcBootstrapOrchestrator> orchestratorByInstanceId = new ConcurrentHashMap<>();
    
    // 1???¤í–‰ ë³´ì¥ (?¸ìŠ¤?´ìŠ¤ë³?
    private final AtomicBoolean started = new AtomicBoolean(false);
    
    // ê³µí†µ ?¼ì´ë¸ŒëŸ¬ë¦??¬ìš©
    private final PolicyResolver policyResolver;
    private MappingSyncService mappingSyncService; // hubId ?ë“ ??ì´ˆê¸°??
    private EndpointSyncService endpointSyncService; // hubId ?ë“ ??ì´ˆê¸°??
    private final EndpointStorage endpointStorage;
    private final InstanceConfigStorage configStorage;
    private final SchemaStorage schemaStorage;
    private DirectCryptoAdapter directCryptoAdapter;
    private final HubIdManager hubIdManager; // ?„ì—­ hubId ê´€ë¦?
    private final InstanceIdProvider instanceIdProvider; // core?ì„œ ?œê³µ?˜ëŠ” instanceId ê´€ë¦?
    
    // Wrapper ?„ìš©
    private JdbcSchemaSyncService schemaSyncService;
    private JdbcSchemaCollector schemaCollector;
    private final ProxyConfig config;
    private final String originalUrl;
    
    // ì²?ë¶€????Connection?ì„œ ì¶”ì¶œ??ë©”í??°ì´??(?¬ë“±ë¡Â·ì´ë¯??¤í–‰??ë¶„ê¸°?ì„œ Connection ?†ì´ ?¬ìš©)
    private volatile String storedDbVendor;
    private volatile String storedHost;
    private volatile int storedPort;
    private volatile String storedDatabase;
    private volatile String storedSchema;
    
    // ?•ì±… ë§¤í•‘ ?™ê¸°???œë¹„??(AOP?€ ?™ì¼??êµ¬ì¡°)
    private JdbcPolicyMappingSyncService policyMappingSyncService;
    
    // Hub ?Œë¦¼ ?œë¹„??(instanceId??1ê°?ê³µìœ , ì»¤ë„¥???€?ì„œ ?¬ì‚¬??
    private volatile HubNotificationService notificationService;

    // ?¤í‚¤ë§?ê°•ì œ ë¦¬ë¡œ?œìš©: ?ë³¸ JDBC URL ?•ë³´ (?¤ì´?°ë¸Œ ?œë¼?´ë²„ë¡?Connection ?ì„±)
    private volatile String nativeJdbcUrl;
    private volatile java.util.Properties nativeJdbcProperties;
    // DadpJdbcDriver?ì„œ ?„ë‹¬ë°›ì? ?ë³¸ ?‘ì† Properties (user/password ?¬í•¨)
    private volatile java.util.Properties originalConnectionProperties;

    // ì´ˆê¸°???„ë£Œ ?Œë˜ê·?
    private volatile boolean initialized = false;
    private volatile String cachedDatasourceId = null;
    // hubId??HubIdManager?ì„œ ?„ì—­?¼ë¡œ ê´€ë¦?(cachedHubId ?„ë“œ ?œê±°)
    
    /**
     * ?ì„±??(Connection ?†ìŒ, instanceId??1?¸íŠ¸ ê³µìœ  ???¬ìš©).
     * runBootstrapFlow(Connection) ?¸ì¶œ ??ì²?ë¶€?…ì—?œë§Œ Connection ?¬ìš©.
     */
    public JdbcBootstrapOrchestrator(String originalUrl, ProxyConfig config) {
        this.originalUrl = originalUrl;
        this.config = config;
        
        // HubIdManager ì´ˆê¸°??(?„ì—­ hubId ê´€ë¦?
        java.util.Map<String, String> urlParams = config.getUrlParams();
        this.instanceIdProvider = new InstanceIdProvider(urlParams);
        String instanceId = this.instanceIdProvider.getInstanceId();
        
        // InstanceConfigStorage ì´ˆê¸°??(instanceId ?¬ìš©)
        this.configStorage = new InstanceConfigStorage(
            StoragePathResolver.resolveStorageDir(instanceId),
            "proxy-config.json"
        );
        
        // SchemaStorage ì´ˆê¸°??(instanceId ?¬ìš©)
        this.schemaStorage = new SchemaStorage(instanceId);
        this.hubIdManager = new HubIdManager(
            configStorage,
            config.getHubUrl(),
            instanceIdProvider,
            (oldHubId, newHubId) -> {
                // hubId ë³€ê²???MappingSyncService ?¬ìƒ??
                if (newHubId != null && !newHubId.equals(oldHubId)) {
                    log.debug("hubId changed: {} -> {}, recreating MappingSyncService", oldHubId, newHubId);
                    initializeServicesWithHubId(newHubId);
                }
            }
        );
        
        // PolicyResolver ì´ˆê¸°??(?±ê???
        this.policyResolver = PolicyResolver.getInstance(instanceId);
        
        // EndpointStorage ì´ˆê¸°??(instanceIdë¥??¬ìš©?˜ì—¬ ê²½ë¡œ ?ì„±: ./dadp/wrapper/instanceId)
        this.endpointStorage = new EndpointStorage(instanceId);
        
        // ?¤í‚¤ë§??˜ì§‘ê¸?ì´ˆê¸°??(Connection ?„ë“œ ?†ìŒ, collectSchemas(Connection) ?¸ì¶œ ?œì ???„ë‹¬)
        this.schemaCollector = new JdbcSchemaCollector(null, config);
        
        // ?¤í‚¤ë§??™ê¸°???œë¹„??ì´ˆê¸°??(V1 API ?¬ìš©: /hub/api/v1/proxy)
        this.schemaSyncService = new JdbcSchemaSyncService(
            config.getHubUrl(),
            schemaCollector,
            "/hub/api/v1/proxy",  // V1 API ê²½ë¡œ
            config,
            policyResolver,
            hubIdManager,
            5,      // maxRetries
            3000,   // initialDelayMs
            2000    // backoffMs
        );
        
        // MappingSyncService?€ EndpointSyncService??hubIdê°€ ?„ìš”?˜ë?ë¡??˜ì¤‘??ì´ˆê¸°??
    }
    
    /**
     * instanceId???¤ì??¤íŠ¸?ˆì´??1?¸íŠ¸ ê³µìœ : ìºì‹œ?ì„œ ì¡°íšŒ ?ëŠ” ?ì„±.
     *
     * @param instanceId ?¸ìŠ¤?´ìŠ¤ ë³„ì¹­ (JDBC URL?ì„œ ì¶”ì¶œ)
     * @param originalUrl JDBC URL
     * @param config Proxy ?¤ì •
     * @return ?´ë‹¹ instanceId???¤ì??¤íŠ¸?ˆì´??(ê³µìœ )
     */
    public static JdbcBootstrapOrchestrator getOrCreate(String instanceId, String originalUrl, ProxyConfig config) {
        return orchestratorByInstanceId.computeIfAbsent(instanceId, k -> new JdbcBootstrapOrchestrator(originalUrl, config));
    }
    
    /**
     * Connection?ì„œ ë©”í??°ì´??ì¶”ì¶œ ???€??(ì²?ë¶€??1?? ?¬ë“±ë¡Â·ì´ë¯??¤í–‰??ë¶„ê¸°?ì„œ ?¬ìš©).
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

            // Oracle: getCatalog()??null??ë°˜í™˜?˜ë?ë¡??œë¹„?¤ëª… ?ëŠ” ?¤í‚¤ë§ˆë¡œ ?€ì²?
            if ((storedDatabase == null || storedDatabase.trim().isEmpty()) && "oracle".equals(storedDbVendor)) {
                storedDatabase = extractDatabaseFromOracleUrl(originalUrl);
                if (storedDatabase == null || storedDatabase.trim().isEmpty()) {
                    storedDatabase = storedSchema; // ?¤í‚¤ë§ˆë? databaseë¡??¬ìš©
                }
                log.debug("Oracle database fallback value set: {}", storedDatabase);
            }

            // ?¤ì´?°ë¸Œ JDBC URL ?€??(?¤í‚¤ë§?ê°•ì œ ë¦¬ë¡œ????Connection ?ì„±??
            try {
                this.nativeJdbcUrl = metaData.getURL();
                // ?ë³¸ ?‘ì† Properties ?°ì„  ?¬ìš© (?•í™•??user/password ?¬í•¨)
                // DatabaseMetaData.getUserName()?€ MySQL?ì„œ "user@host" ?•íƒœë¥?ë°˜í™˜?????ˆì–´ ?¸ì¦ ?¤íŒ¨ ê°€??
                if (originalConnectionProperties != null) {
                    this.nativeJdbcProperties = (java.util.Properties) originalConnectionProperties.clone();
                } else {
                    this.nativeJdbcProperties = new java.util.Properties();
                    String userName = metaData.getUserName();
                    if (userName != null) {
                        // MySQL: "root@172.20.0.3" ??"root" (@ ?´í›„ ?œê±°)
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
    
    /**
     * DadpJdbcDriver?ì„œ ?„ë‹¬ë°›ì? ?ë³¸ ?‘ì† Properties ?€??(user/password ?¬í•¨)
     */
    public void setNativeConnectionProperties(java.util.Properties props) {
        if (this.originalConnectionProperties == null && props != null) {
            this.originalConnectionProperties = props;
            // nativeJdbcProperties??password ë³‘í•© (storeMetadataFrom?ì„œ userë§??€?¥ë˜ë¯€ë¡?
            if (this.nativeJdbcProperties != null && props.getProperty("password") != null) {
                this.nativeJdbcProperties.setProperty("password", props.getProperty("password"));
            }
        }
    }

    /** ?€?¥ëœ ë©”í??°ì´?°ë¡œ datasourceId ë¡œë“œ ???¬ìš© (?´ë? ?¤í–‰???¬ë“±ë¡???Connection ?†ì´ ?¬ìš©) */
    public String getStoredDbVendor() { return storedDbVendor; }
    public String getStoredHost() { return storedHost; }
    public int getStoredPort() { return storedPort; }
    public String getStoredDatabase() { return storedDatabase; }
    public String getStoredSchema() { return storedSchema; }
    public String getStoredOriginalUrl() { return originalUrl; }
    public boolean hasStoredMetadata() { return storedDbVendor != null && storedHost != null && storedDatabase != null; }
    
    /**
     * ë¶€???Œë¡œ???¤í–‰. instanceId??1?¸íŠ¸ ê³µìœ  ??ì²?ì»¤ë„¥?˜ì—?œë§Œ Connection ?¬ìš©.
     *
     * @param connection JDBC Connection (ì²?ë¶€?????¤í‚¤ë§??˜ì§‘Â·ë©”í??°ì´??ì¶”ì¶œ?ë§Œ ?¬ìš©, ?€?¥í•˜ì§€ ?ŠìŒ)
     * @return ì´ˆê¸°???„ë£Œ ?¬ë?
     */
    public boolean runBootstrapFlow(Connection connection) {
        // instanceId ê¸°ë°˜?¼ë¡œ ?„ì—­ 1???¤í–‰ ë³´ì¥ (core??InstanceIdProvider ?¬ìš©)
        String instanceId = instanceIdProvider.getInstanceId();
        AtomicBoolean instanceStarted = instanceStartedMap.computeIfAbsent(instanceId, k -> new AtomicBoolean(false));
        
        if (!instanceStarted.compareAndSet(false, true)) {
            log.trace("JdbcBootstrapOrchestrator already executed (instanceId={})", instanceId);
            // ?´ë? ?¤í–‰??ê²½ìš°: ?œë¹„?¤ëŠ” ì²?ë¶€?…ì—???´ë? ì´ˆê¸°?”ë¨. ?¬ì´ˆê¸°í™”?˜ì? ?ŠìŒ (ì»¤ë„¥?˜ë§ˆ??HubNotificationService ??ì¤‘ë³µ ?ì„± ë°©ì?)
            String loadedHubId = hubIdManager.loadFromStorage();
            if (loadedHubId != null && !loadedHubId.trim().isEmpty()) {
                this.initialized = true;
                // datasourceId???€?¥ëœ ë©”í??°ì´?°ë¡œ ë¡œë“œ (Connection ?¬ìš© ????
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
            // hubIdê°€ ?†ìœ¼ë©?ì´ˆê¸°???¤íŒ¨ë¡?ê°„ì£¼
            return false;
        }
        
        // ?¸ìŠ¤?´ìŠ¤ë³??¤í–‰ ?Œë˜ê·¸ë„ ?¤ì •
        if (!started.compareAndSet(false, true)) {
            log.trace("This instance has already been executed.");
            return initialized;
        }
        
        try {
            // Hub URL???†ìœ¼ë©??¤í–‰?˜ì? ?ŠìŒ
            String hubUrl = config.getHubUrl();
            if (hubUrl == null || hubUrl.trim().isEmpty()) {
                log.debug("Hub URL not configured, skipping bootstrap flow.");
                return false;
            }
            
            log.info("JDBC Wrapper bootstrap flow orchestrator starting");
            
            // Connection?ì„œ ë©”í??°ì´??ì¶”ì¶œÂ·?€??(?¬ë“±ë¡Â·ì´ë¯??¤í–‰??ë¶„ê¸°?ì„œ Connection ?†ì´ ?¬ìš©)
            storeMetadataFrom(connection);
            
            // 1. DB ?¤í‚¤ë§?1???˜ì§‘ (?¸ì¶œ ?œì ??Connection ?„ë‹¬, ?„ë“œë¡?ë³´ê??˜ì? ?ŠìŒ)
            log.info("Step 1: DB schema collection (one-time)");
            List<SchemaMetadata> currentSchemas = schemaSyncService.collectSchemasWithRetry(connection, 5, 2000);
            if (currentSchemas == null || currentSchemas.isEmpty()) {
                log.warn("Schema collection failed or returned 0 (continuing in fail-open mode)");
            } else {
                log.info("Schema collection completed: {} schemas", currentSchemas.size());
            }
            
            // 2. ?êµ¬?€?¥ì†Œ ë¡œë“œ (hubId, ?•ì±…ë§¤í•‘, ?”ë“œ?¬ì¸?? ?¤í‚¤ë§?ëª©ë¡, datasourceId ??
            log.info("Step 2: Loading data from persistent storage");
            String hubId = hubIdManager.loadFromStorage();
            loadOtherDataFromPersistentStorage();
            
            // 2.5. Try loading from exported config file (initial bootstrap or policy update)
            // ExportedConfigLoader internally compares policyVersion and skips if current >= file
            {
                String storageDir = StoragePathResolver.resolveStorageDir(instanceId);
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

            // 3. ?êµ¬?€?¥ì†Œ DB ?¤í‚¤ë§?vs 1?¨ê³„ ?˜ì§‘ ê²°ê³¼ ë¹„êµ (?ì„±/?±ë¡/?? œ ?ë‹¨), ?€??
            if (currentSchemas != null && !currentSchemas.isEmpty()) {
                saveSchemasToStorage(currentSchemas);
            }

            // 3. Hub ?±ë¡ ë°??¤í‚¤ë§??±ë¡ (hubIdê°€ ?†ìœ¼ë©??±ë¡, ?ˆìœ¼ë©??¤í‚¤ë§ˆë§Œ ?™ê¸°??
            log.info("Step 3: Hub registration and schema registration");
            boolean schemaRegistrationCompleted = false;

            if (hubId == null) {
                // hubIdê°€ ?†ìœ¼ë©?Datasource ?±ë¡ ë°??¤í‚¤ë§??±ë¡
                schemaRegistrationCompleted = registerWithHub();
                // registerWithHub()?ì„œ hubIdë¥??¤ì •?˜ë?ë¡?HubIdManager?ì„œ ?¤ì‹œ ë¡œë“œ
                hubId = hubIdManager.getCachedHubId();
            } else {
                // hubIdê°€ ?ˆìœ¼ë©??ì„± ?íƒœ ?¤í‚¤ë§ˆë§Œ Hub???±ë¡
                // ?¬ë“±ë¡ì´ ë°œìƒ?????ˆìœ¼ë¯€ë¡?HubIdManager?ì„œ ìµœì‹  hubId ?•ì¸
                String oldHubId = hubId;
                schemaRegistrationCompleted = ensureSchemasSyncedToHub(hubId);
                // ?¬ë“±ë¡ì´ ë°œìƒ?ˆë‹¤ë©?HubIdManager?ì„œ ìµœì‹  hubId ê°€?¸ì˜¤ê¸?
                String newHubId = hubIdManager.getCachedHubId();
                if (newHubId != null && !newHubId.equals(oldHubId)) {
                    log.info("hubId changed due to re-registration: {} -> {}", oldHubId, newHubId);
                    hubId = newHubId;
                }
            }
            
            // hubIdê°€ ?†ìœ¼ë©??¤ìŒ ?¨ê³„ ì§„í–‰ ë¶ˆê?
            if (hubId == null || hubId.trim().isEmpty()) {
                log.warn("Cannot initialize services without hubId.");
                initialized = false;
                return false;
            }
            
            // HubIdManager??hubId ?¤ì • (?„ì—­ ê´€ë¦?
            hubIdManager.setHubId(hubId, true);
            
            // 4. ?œë¹„??ì´ˆê¸°??(hubIdê°€ ?ˆìœ¼ë©??”ë³µ?¸í™” ?œë¹„?¤ëŠ” ??ƒ ì´ˆê¸°??
            // ì¤‘ìš”: Hub ?±ë¡???¤íŒ¨?´ë„ ?€?¥ëœ hubId?€ ?”ë“œ?¬ì¸???•ë³´ë¡??”ë³µ?¸í™”??ê°€?¥í•´????
            log.info("Step 4: Service initialization (crypto service initialized regardless of Hub registration result)");
            initializeServicesWithHubId(hubId);
            
            // 5. ?•ì±… ë§¤í•‘ ?™ê¸°???œë¹„??ì´ˆê¸°??(?¤í‚¤ë§??±ë¡???„ë£Œ??ê²½ìš°?ë§Œ)
            if (schemaRegistrationCompleted) {
                log.info("Step 5: Policy mapping sync service initialization");
                initializePolicyMappingSyncService(hubId);
                
                // 6. ?¤í‚¤ë§??±ë¡ ?„ë£Œ ???•ì±… ë§¤í•‘ ?™ê¸°???œë¹„???œì„±??(30ì´?ì£¼ê¸° ë²„ì „ ì²´í¬ ?œì‘)
                initialized = true;
                if (policyMappingSyncService != null) {
                    policyMappingSyncService.setInitialized(true, hubId);
                }
                log.info("JDBC Wrapper bootstrap flow completed: hubId={}, datasourceId={}", hubIdManager.getCachedHubId(), cachedDatasourceId);
            } else {
                // Hub ?±ë¡???¤íŒ¨?ˆì?ë§??€?¥ëœ hubIdë¡??”ë³µ?¸í™” ?œë¹„?¤ëŠ” ì´ˆê¸°?”ë¨
                // ?•ì±… ë§¤í•‘ ?™ê¸°?”ëŠ” ?˜ì¤‘??Hub ?°ê²°??ë³µêµ¬?˜ë©´ ?¬ì‹œ?„ë¨
                log.warn("Hub registration failed: crypto service initialized but policy mapping sync not started. Will retry when Hub connection is restored.");
                initialized = true; // ?”ë³µ?¸í™” ?œë¹„?¤ëŠ” ?¬ìš© ê°€?¥í•˜ë¯€ë¡?ì´ˆê¸°???„ë£Œë¡?ê°„ì£¼
                log.info("JDBC Wrapper bootstrap flow completed (limited): hubId={}, datasourceId={}, crypto available",
                        hubIdManager.getCachedHubId(), cachedDatasourceId);
            }
            return true;
            
        } catch (Exception e) {
            // ?ˆì¸¡ ê°€?¥í•œ ë¬¸ì œ: ë¶€???Œë¡œ???¤íŒ¨ (Hub ?°ê²° ë¶ˆê? ??
            // ?¤íƒ ?¸ë ˆ?´ìŠ¤ ì¶œë ¥ ê¸ˆì? (exception-handling.md ê·œì•½)
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.trim().isEmpty()) {
                errorMessage = e.getClass().getSimpleName();
            }
            log.warn("Bootstrap flow failed: {}", errorMessage);
            return false;
        }
    }
    
    /**
     * ?êµ¬?€?¥ì†Œ?ì„œ ?°ì´??ë¡œë“œ (hubId??HubIdManager?ì„œ ê´€ë¦¬í•˜ë¯€ë¡??œê±°)
     */
    private void loadOtherDataFromPersistentStorage() {
        // PolicyResolver???±ê??¤ì´ë¯€ë¡??´ë? ë¡œë“œ??
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
        
        // EndpointStorage?ì„œ ?”ë“œ?¬ì¸???•ë³´ ë¡œë“œ
        EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
        if (endpointData != null) {
            log.debug("Endpoint info loaded from persistent storage: cryptoUrl={}, hubId={}, version={}",
                    endpointData.getCryptoUrl(), endpointData.getHubId(), endpointData.getVersion());
        }
        
        // SchemaStorage?ì„œ ?¤í‚¤ë§?ë¡œë“œ
        List<SchemaMetadata> storedSchemas = schemaStorage.loadSchemas();
        if (!storedSchemas.isEmpty()) {
            log.debug("Schemas loaded from persistent storage: {} schemas", storedSchemas.size());
        }
        
        // DatasourceStorage?ì„œ datasourceId ë¡œë“œ (?€?¥ëœ ë©”í??°ì´???¬ìš©, Connection ?†ìŒ)
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
    
    /**
     * Hub???±ë¡ (V1 API: Datasource ?±ë¡?ì„œ hubId?€ datasourceIdë¥??™ì‹œ??ë°›ìŒ)
     * 
     * @return ?¤í‚¤ë§??±ë¡ ?„ë£Œ ?¬ë? (hubId ?±ë¡ ë°??¤í‚¤ë§??±ë¡ ?±ê³µ ??true)
     */
    private boolean registerWithHub() {
        String instanceId = instanceIdProvider.getInstanceId();
        
        // V1 API: Datasource ?±ë¡ (?¸ì¦???•ì¸/?¤ìš´ë¡œë“œ ?†ìŒ, HTTP Hub ?ëŠ” ê¸°ë³¸ ? ë¢° ?€?¥ì†Œ ?¬ìš©)
        log.info("Hub Datasource registration starting: instanceId={}", instanceId);
        DatasourceRegistrationService.DatasourceInfo datasourceInfo = registerDatasource(null);
        if (datasourceInfo == null) {
            log.warn("Datasource registration failed: Hub unreachable or response error");
            return false;
        }
        
        // hubId?€ datasourceId ?€??
        String hubId = datasourceInfo.getHubId();
        if (hubId == null || hubId.trim().isEmpty()) {
            log.warn("Datasource registration response missing hubId");
            return false;
        }
        
        // HubIdManager??hubId ?¤ì • (?„ì—­ ê´€ë¦? ?êµ¬?€?¥ì†Œ???ë™ ?€??
        hubIdManager.setHubId(hubId, true);
        log.info("Hub Datasource registration completed: hubId={}, datasourceId={}", hubId, datasourceInfo.getDatasourceId());
        
        // EndpointSyncService ì´ˆê¸°??(instanceIdë¥??¬ìš©?˜ì—¬ ê²½ë¡œ ?ì„±)
        String endpointStorageDir = StoragePathResolver.resolveStorageDir(instanceId);
        String endpointFileName = "crypto-endpoints.json";
        this.endpointSyncService = new EndpointSyncService(
            config.getHubUrl(),
            hubId,
            instanceId,
            endpointStorageDir,
            endpointFileName
        );
        
        // datasourceIdê°€ ?¤ì •????schemaCollector?€ schemaSyncService ?¬ìƒ??(Connection ?„ë“œ ?†ìŒ)
        if (cachedDatasourceId != null && !cachedDatasourceId.trim().isEmpty()) {
            this.schemaCollector = new JdbcSchemaCollector(cachedDatasourceId, config);
            this.schemaSyncService = new JdbcSchemaSyncService(
                config.getHubUrl(),
                schemaCollector,
                "/hub/api/v1/proxy",  // V1 API ê²½ë¡œ
                config,
                policyResolver,
                hubIdManager,    // HubIdManager ?„ë‹¬ (?„ì—­ hubId ê´€ë¦?
                5,      // maxRetries
                3000,   // initialDelayMs
                2000    // backoffMs
            );
            log.debug("schemaCollector recreated after datasourceId set: datasourceId={}", cachedDatasourceId);
        }
        
        // ?€?¥ëœ ?¤í‚¤ë§ˆì— datasourceId ?…ë°?´íŠ¸ (Datasource ?±ë¡ ?„ì— ?€?¥ëœ ?¤í‚¤ë§ˆì— datasourceIdê°€ ?†ì„ ???ˆìŒ)
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
        
        // 3?¨ê³„: ?ì„± ?íƒœ ?¤í‚¤ë§??„ì†¡ (AOP?€ ?™ì¼??êµ¬ì¡°)
        if (schemaSyncService == null) {
            log.warn("JdbcSchemaSyncService unavailable, cannot perform schema sync.");
            return false;
        }
        
        List<SchemaMetadata> createdSchemas = schemaStorage.getCreatedSchemas();
        if (!createdSchemas.isEmpty()) {
            log.info("Step 3: Sending CREATED schemas to Hub: hubId={}, schemaCount={}", hubId, createdSchemas.size());
            boolean synced = syncCreatedSchemasToHub(hubId, createdSchemas);
            if (synced) {
                // Hub??/schemas/sync ?”ë“œ?¬ì¸???‘ë‹µ??ë°›ì•˜?¼ë?ë¡?REGISTEREDë¡?ë³€ê²?
                List<String> schemaKeys = new java.util.ArrayList<>();
                for (SchemaMetadata schema : createdSchemas) {
                    if (schema != null) {
                        schemaKeys.add(schema.getKey());
                    }
                }
                int updatedCount = schemaStorage.updateSchemasStatus(schemaKeys, SchemaMetadata.Status.REGISTERED);
                log.info("CREATED schemas sent and status updated: {} schemas (CREATED -> REGISTERED)", updatedCount);
                log.info("Hub registration completed: hubId={}", hubId);
                return true;  // ?¤í‚¤ë§??±ë¡ ?±ê³µ
            } else {
                log.warn("CREATED schemas send failed (no Hub response)");
                return false;  // ?¤í‚¤ë§??±ë¡ ?¤íŒ¨
            }
        } else {
            log.info("Step 3: No CREATED schemas (only already-registered schemas exist)");
        log.info("Hub registration completed: hubId={}", hubId);
            return true;  // ?±ë¡???¤í‚¤ë§ˆê? ?†ìœ¼ë©??„ë£Œë¡?ê°„ì£¼
        }
        
        // ?”ë“œ?¬ì¸???™ê¸°?”ëŠ” ë²„ì „ ì²´í¬ ???•ì±… ë§¤í•‘ê³??¨ê»˜ ë°›ì•„?¤ë?ë¡??¬ê¸°?œëŠ” ?œê±°
        // PolicyMappingSyncOrchestrator??ì½œë°±?ì„œ ?”ë“œ?¬ì¸???•ë³´ë¥?ë°›ì•„???€?¥í•¨
    }
    
    /**
     * Hub???¸ìŠ¤?´ìŠ¤ ?±ë¡ (hubId ë°œê¸‰) - AOP?€ ?™ì¼
     * 
     * @param hubUrl Hub URL
     * @param instanceId ?¸ìŠ¤?´ìŠ¤ ID
     * @return ë°œê¸‰ë°›ì? hubId, ?¤íŒ¨ ??null
     */
    private String registerInstance(String hubUrl, String instanceId) {
        // V1 API ?¬ìš©: /hub/api/v1/proxy/datasources/register
        // V1 API???¸ìŠ¤?´ìŠ¤ ?±ë¡ê³?datasource ?±ë¡???™ì‹œ??ì²˜ë¦¬?˜ë?ë¡?
        // ??ë©”ì„œ?œëŠ” ?¬ìš©?˜ì? ?Šê³  registerDatasource()?ì„œë§?ì²˜ë¦¬
        // registerDatasource()?ì„œ hubIdë¥?ë°›ì•„??
        log.warn("registerInstance() is deprecated. Use registerDatasource() to obtain hubId.");
            return null;
    }
    
    /**
     * Datasource ?±ë¡ (hubId?€ datasourceIdë¥??™ì‹œ??ë°›ìŒ)
     * 
     * @param caCertPath Root CA ?¸ì¦??ê²½ë¡œ (null?´ë©´ HTTP/ê¸°ë³¸ ? ë¢° ?€?¥ì†Œ ?¬ìš©)
     * @return DatasourceInfo (hubId?€ datasourceId ?¬í•¨), ?¤íŒ¨ ??null
     */
    private DatasourceRegistrationService.DatasourceInfo registerDatasource(String caCertPath) {
        try {
            // ?€?¥ëœ ë©”í??°ì´???¬ìš© (?¬ë“±ë¡Â·ì²« ë¶€??ëª¨ë‘, Connection ?„ë“œ ?†ìŒ)
            if (!hasStoredMetadata()) {
                log.warn("No stored metadata: skipping registerDatasource");
                return null;
            }
            String dbVendor = storedDbVendor;
            String host = storedHost;
            int port = storedPort;
            String database = storedDatabase;
            String schema = storedSchema;
            
            // Hub??Datasource ?±ë¡/ì¡°íšŒ ?”ì²­ (hubId?€ datasourceIdë¥??™ì‹œ??ë°›ìŒ)
            // ?¬ë“±ë¡???Hubê°€ hubVersion = currentVersion + 1ë¡??¤ì •?????ˆë„ë¡?currentVersion ?„ì†¡
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
                
                // datasourceId ?€??
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
     * Root CA ?¸ì¦???•ì¸ ë°??¤ì •
     * 
     * ?Œë¡œ??
     * 1. ?˜ë™ ê²½ë¡œ(DADP_CA_CERT_PATH / dadp.ca.cert.path) ?•ì¸
     * 2. ?€?¥ì†Œ??ê¸°ì¡´ ?¸ì¦???Œì¼ ?•ì¸
     * 3. ê²€ì¦???ë°˜í™˜ (?¤ìš´ë¡œë“œ???˜ì? ?ŠìŒ)
     * 
     * @param hubUrl Hub URL
     * @param instanceId ?¸ìŠ¤?´ìŠ¤ ID
     * @return ?¸ì¦???Œì¼ ê²½ë¡œ (ê²€ì¦??„ë£Œ ??ê²½ë¡œ, ?†ê±°???¤íŒ¨ ??null)
     */
    private String ensureRootCACertificate(String hubUrl, String instanceId) {
        log.info("Root CA certificate verification starting: hubUrl={}, instanceId={}", hubUrl, instanceId);
        
        // DADP_CA_CERT_PATHê°€ ?˜ë™?¼ë¡œ ?¤ì •?˜ì–´ ?ˆìœ¼ë©?ê·¸ê²ƒ???¬ìš© (ìµœìš°??
        String manualCaCertPath = System.getProperty("dadp.ca.cert.path");
        if (manualCaCertPath == null || manualCaCertPath.trim().isEmpty()) {
            manualCaCertPath = System.getenv("DADP_CA_CERT_PATH");
        }
        if (manualCaCertPath != null && !manualCaCertPath.trim().isEmpty()) {
            // ?˜ë™ ?¤ì •???¸ì¦?œë„ ê²€ì¦??„ìš”
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
            // ?€?¥ì†Œ???¸ì¦???•ì¸ (?¤ìš´ë¡œë“œ ?†ìŒ)
            boolean certExists = java.nio.file.Files.exists(caCertPath);
            
            if (certExists) {
                log.info("Root CA certificate found in storage: path={}", caCertPath);
            } else {
                log.info("Root CA certificate not found in storage (manual config or file placement required): path={}", caCertPath);
                return null;
            }
            
            // ê²€ì¦?
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
     * SSLContext ?ì„± ê²€ì¦?
     * 
     * ?¸ì¦???Œì¼ë¡??¤ì œë¡?SSLContextë¥??ì„±?????ˆëŠ”ì§€ ?•ì¸?©ë‹ˆ??
     * 
     * @param caCertPath ?¸ì¦???Œì¼ ê²½ë¡œ
     * @return SSLContext ?ì„± ?±ê³µ ?¬ë?
     */
    private boolean verifySSLContextCreation(String caCertPath) {
        try {
            // ?¸ì¦???Œì¼ ?½ê¸°
            String pem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(caCertPath)), "UTF-8");
            if (pem == null || pem.trim().isEmpty()) {
                log.warn("SSLContext creation verification failed: certificate file is empty");
                return false;
            }
            
            // PEM ?•ì‹ ?¸ì¦?œë? X.509 ?¸ì¦?œë¡œ ë³€??
            String certContent = pem.replace("-----BEGIN CERTIFICATE-----", "")
                                    .replace("-----END CERTIFICATE-----", "")
                                    .replaceAll("\\s", "");
            byte[] certBytes = java.util.Base64.getDecoder().decode(certContent);
            java.security.cert.CertificateFactory certFactory = 
                java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.X509Certificate caCert = 
                (java.security.cert.X509Certificate) certFactory.generateCertificate(
                    new java.io.ByteArrayInputStream(certBytes));
            
            // TrustStore ?ì„± ë°?DADP CA ì¶”ê?
            java.security.KeyStore trustStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("dadp-root-ca", caCert);
            
            // TrustManagerFactory ?ì„±
            javax.net.ssl.TrustManagerFactory trustManagerFactory = 
                javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            
            // SSLContext ?ì„±
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), new java.security.SecureRandom());
            
            // SSLContext ?ì„± ?±ê³µ
            return true;
        } catch (Exception e) {
            // SSLContext ?ì„± ?¤íŒ¨
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.trim().isEmpty()) {
                errorMessage = e.getClass().getSimpleName();
            }
            log.warn("SSLContext creation verification failed: error={}", errorMessage);
            return false;
        }
    }
    
    /**
     * Root CA ?¸ì¦??? íš¨??ê²€ì¦?
     * 
     * @param certPath ?¸ì¦???Œì¼ ê²½ë¡œ
     * @return ? íš¨?˜ë©´ true, ? íš¨?˜ì? ?Šìœ¼ë©?false
     */
    private boolean validateRootCACertificate(java.nio.file.Path certPath) {
        try {
            // ?Œì¼ ?½ê¸°
            String pem = new String(java.nio.file.Files.readAllBytes(certPath), "UTF-8");
            if (pem == null || pem.trim().isEmpty()) {
                log.warn("Root CA certificate file is empty");
                return false;
            }
            
            // PEM ?•ì‹ ?¸ì¦?œë? X.509 ?¸ì¦?œë¡œ ë³€??
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
            
            // ? íš¨ê¸°ê°„ ê²€ì¦?
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
     * 1?¨ê³„?ì„œ ?˜ì§‘???¤í‚¤ë§ˆë? ?êµ¬?€?¥ì†Œ?€ ë¹„êµ ???€??(DB ?¬ìˆ˜ì§??†ìŒ).
     *
     * @param currentSchemas 1?¨ê³„ collectSchemasWithRetry() ê²°ê³¼ (null?´ë©´ ë¬´ì‹œ)
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
     * Hub???¤í‚¤ë§ˆê? ?™ê¸°?”ë˜???ˆëŠ”ì§€ ?•ì¸?˜ê³  ?„ìš”???¬ì „??
     * 
     * @param hubId Hub ID
     * @return ?¤í‚¤ë§??±ë¡ ?„ë£Œ ?¬ë? (?ì„± ?íƒœ ?¤í‚¤ë§ˆê? ?†ê±°???±ë¡ ?±ê³µ ??true)
     */
    private boolean ensureSchemasSyncedToHub(String hubId) {
        // ?ì„± ?íƒœ ?¤í‚¤ë§??„ì†¡
        List<SchemaMetadata> createdSchemas = schemaStorage.getCreatedSchemas();
        if (!createdSchemas.isEmpty()) {
            log.info("Sending CREATED schemas to Hub: hubId={}, schemaCount={}", hubId, createdSchemas.size());
            boolean synced = syncCreatedSchemasToHub(hubId, createdSchemas);
            if (synced) {
                // Hub??/schemas/sync ?”ë“œ?¬ì¸???‘ë‹µ??ë°›ì•˜?¼ë?ë¡?REGISTEREDë¡?ë³€ê²?
                List<String> schemaKeys = new java.util.ArrayList<>();
                for (SchemaMetadata schema : createdSchemas) {
                    if (schema != null) {
                        schemaKeys.add(schema.getKey());
                    }
                }
                schemaStorage.updateSchemasStatus(schemaKeys, SchemaMetadata.Status.REGISTERED);
                log.info("CREATED schemas sent and status updated: {} schemas (CREATED -> REGISTERED)",
                        createdSchemas.size());
                return true;  // ?¤í‚¤ë§??±ë¡ ?±ê³µ
            } else {
                // ?¤í‚¤ë§??„ì†¡ ?¤íŒ¨: 404 ?‘ë‹µ ê°€?¥ì„± -> ?¬ë“±ë¡??„ìš”
                log.info("CREATED schemas send failed (possible 404): hubId={}, attempting re-registration", hubId);
                boolean reRegistered = registerWithHub();
                if (reRegistered) {
                    String newHubId = hubIdManager.getCachedHubId(); // HubIdManager?ì„œ ìµœì‹  hubId ê°€?¸ì˜¤ê¸?
                    log.info("Re-registration completed: new hubId={}", newHubId);
                    // ?¬ë“±ë¡????¤í‚¤ë§??¬ì „???œë„
                    return ensureSchemasSyncedToHub(newHubId);
                } else {
                    log.warn("Re-registration failed");
                    return false;
                }
            }
        } else {
            log.debug("No schemas to send, assumed already synced with Hub");
            return true;  // ?±ë¡???¤í‚¤ë§ˆê? ?†ìœ¼ë©??„ë£Œë¡?ê°„ì£¼
        }
    }
    
    /**
     * ?ì„± ?íƒœ ?¤í‚¤ë§ˆë§Œ Hub???„ì†¡
     */
    private boolean syncCreatedSchemasToHub(String hubId, List<SchemaMetadata> createdSchemas) {
        if (createdSchemas == null || createdSchemas.isEmpty()) {
            return false;
        }
        
        // ?„ì†¡ ?„ì— datasourceId ?¤ì • (?€?¥ëœ ?¤í‚¤ë§ˆì— datasourceIdê°€ ?†ì„ ???ˆìŒ)
        if (cachedDatasourceId != null && !cachedDatasourceId.trim().isEmpty()) {
            for (SchemaMetadata schema : createdSchemas) {
                if (schema != null && (schema.getDatasourceId() == null || schema.getDatasourceId().trim().isEmpty())) {
                    schema.setDatasourceId(cachedDatasourceId);
                    log.trace("Set datasourceId on schema before sending: schema={}.{}.{}, datasourceId={}",
                        schema.getSchemaName(), schema.getTableName(), schema.getColumnName(), cachedDatasourceId);
                }
            }
        }
        
        // ?€?¥ëœ ?¤í‚¤ë§ˆë? ì§ì ‘ ?„ì†¡ (syncSpecificSchemasToHub ?¬ìš©)
        // syncSchemaToHub??schemaCollector?ì„œ ?ˆë¡œ ?˜ì§‘?˜ë?ë¡??¬ìš©?˜ì? ?ŠìŒ
        boolean success = schemaSyncService.syncSpecificSchemasToHub(createdSchemas);
        
        // 404 ?‘ë‹µ ì²˜ë¦¬: false ë°˜í™˜ ??404?¸ì? ?•ì¸
        if (!success) {
            // RetryableSchemaSyncService?ì„œ 404ë¥??•ì¸?˜ê³  falseë¥?ë°˜í™˜?ˆì„ ???ˆìŒ
            // ?¬ê¸°?œëŠ” falseë§?ë°˜í™˜?˜ê³ , ?ìœ„?ì„œ ?¬ë“±ë¡?ì²˜ë¦¬
            log.info("Schema sync failed (possible 404), re-registration required");
        }
        
        return success;
    }
    
    /**
     * hubId ?ë“ ???œë¹„??ì´ˆê¸°??
     */
    private void initializeServicesWithHubId(String hubId) {
        // MappingSyncService ì´ˆê¸°??
        // V1 API ?¬ìš©: "/hub/api/v1/proxy"
        String instanceId = instanceIdProvider.getInstanceId();
        this.mappingSyncService = new MappingSyncService(
            config.getHubUrl(),
            hubId,
            instanceId,
            cachedDatasourceId,
            "/hub/api/v1/proxy",  // V1 API ê²½ë¡œ
            policyResolver
        );
        
        // EndpointSyncService ì´ˆê¸°??(instanceIdë¥??¬ìš©?˜ì—¬ ê²½ë¡œ ?ì„±)
        String endpointStorageDir = StoragePathResolver.resolveStorageDir(instanceId);
        String endpointFileName = "crypto-endpoints.json";
        this.endpointSyncService = new EndpointSyncService(
            config.getHubUrl(),
            hubId,
            instanceId,
            endpointStorageDir,
            endpointFileName
        );
        
        // DirectCryptoAdapter ì´ˆê¸°??
        // ì¤‘ìš”: Hub ?±ë¡ ?¤íŒ¨ ?¬ë??€ ë¬´ê??˜ê²Œ ?€?¥ëœ ?”ë“œ?¬ì¸???•ë³´ë¡??”ë³µ?¸í™” ?œë¹„??ì´ˆê¸°??
        // ?´ë ‡ê²??˜ë©´ Hub ?±ë¡??1???±ê³µ????Hub??ë¬¸ì œê°€ ?ˆì–´???”ë³µ?¸í™”??ê³„ì† ?™ì‘ ê°€??
        this.directCryptoAdapter = new DirectCryptoAdapter(config.isFailOpen());
        
        // ?€?¥ëœ ?”ë“œ?¬ì¸???•ë³´ë¡?ë¨¼ì? ì´ˆê¸°??(Hub ?†ì´???™ì‘ ê°€??
        EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
        if (endpointData != null && endpointData.getCryptoUrl() != null && 
            !endpointData.getCryptoUrl().trim().isEmpty()) {
            directCryptoAdapter.setEndpointData(endpointData);
            log.info("Crypto adapter initialized: cryptoUrl={}, hubId={}, version={}",
                    endpointData.getCryptoUrl(), endpointData.getHubId(), endpointData.getVersion());
        }
        
        // Hub ?Œë¦¼ ?œë¹„??1?Œë§Œ ?ì„± (ì²?ë¶€????ì½œë°±+4?¨ê³„?ì„œ ??ë²??¸ì¶œ?????ˆìœ¼ë¯€ë¡?null???Œë§Œ ?ì„±)
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
     * ?•ì±… ë§¤í•‘ ?™ê¸°???œë¹„??ì´ˆê¸°??(AOP?€ ?™ì¼??êµ¬ì¡°)
     */
    private void initializePolicyMappingSyncService(String hubId) {
        try {
            // MappingSyncService?€ EndpointSyncService???´ë? initializeServicesWithHubId?ì„œ ì´ˆê¸°?”ë¨
            // JdbcPolicyMappingSyncService ?ì„± (?¬ë“±ë¡????€??ë©”í??°ì´???¬ìš©, Connection ë¯¸ì „??
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
            
            // ?¬ë“±ë¡?ì½œë°± ?¤ì • (404 ?‘ë‹µ ???¸ì¶œ??
            final JdbcBootstrapOrchestrator self = this;
            policyMappingSyncService.setReregistrationCallback(() -> {
                log.info("Re-registration callback invoked: performing Datasource re-registration");
                // registerWithHub()ë¥??¸ì¶œ?˜ì—¬ Datasource ?¬ë“±ë¡?ë°??¤í‚¤ë§??¬ì „??
                self.registerWithHub();
            });

            // ?¤í‚¤ë§?ê°•ì œ ë¦¬ë¡œ??ì½œë°± ?¤ì • (Hub?ì„œ forceSchemaReload=true ?˜ì‹  ??
            policyMappingSyncService.setSchemaReloadCallback(() -> {
                log.info("Schema force reload callback invoked");
                self.forceReloadSchemas();
            });

            log.info("JdbcPolicyMappingSyncService initialized: hubId={}", hubId);
        } catch (Exception e) {
            log.warn("JdbcPolicyMappingSyncService initialization failed: {}", e.getMessage());
        }
    }
    
    /**
     * URL?ì„œ ?¸ìŠ¤??ì¶”ì¶œ (Oracle URL ?•ì‹ ì§€??
     *
     * ì§€???•ì‹:
     * - MySQL/PostgreSQL: jdbc:dadp:mysql://host:3306/db?hubUrl=...
     * - Oracle thin: jdbc:dadp:oracle:thin:@//host:1521/service?hubUrl=...
     * - Oracle thin SID: jdbc:dadp:oracle:thin:@host:1521:SID?hubUrl=...
     */
    private String extractHostFromUrl(String url, String dbVendor) {
        try {
            // ì¿¼ë¦¬ ?Œë¼ë¯¸í„° ?œê±° (hubUrl??://?€ ?¼ë™ ë°©ì?)
            String baseUrl = url;
            int queryIdx = url.indexOf('?');
            if (queryIdx > 0) {
                baseUrl = url.substring(0, queryIdx);
            }

            if ("oracle".equals(dbVendor)) {
                // Oracle: @// ?ëŠ” @ ?´í›„?ì„œ ?¸ìŠ¤??ì¶”ì¶œ
                int atIdx = baseUrl.indexOf('@');
                if (atIdx >= 0) {
                    String afterAt = baseUrl.substring(atIdx + 1);
                    // @// ?•ì‹ (?œë¹„?¤ëª…)
                    if (afterAt.startsWith("//")) {
                        afterAt = afterAt.substring(2);
                    }
                    // host:port ì¶”ì¶œ
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

            // ê¸°ë³¸ (MySQL, PostgreSQL ??: ://host:port ?•ì‹
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
     * URL?ì„œ ?¬íŠ¸ ì¶”ì¶œ (Oracle URL ?•ì‹ ì§€??
     */
    private int extractPortFromUrl(String url, String dbVendor) {
        try {
            // ì¿¼ë¦¬ ?Œë¼ë¯¸í„° ?œê±° (hubUrl???¬íŠ¸?€ ?¼ë™ ë°©ì?)
            String baseUrl = url;
            int queryIdx = url.indexOf('?');
            if (queryIdx > 0) {
                baseUrl = url.substring(0, queryIdx);
            }

            if ("oracle".equals(dbVendor)) {
                // Oracle: @// ?ëŠ” @ ?´í›„?ì„œ ?¬íŠ¸ ì¶”ì¶œ
                int atIdx = baseUrl.indexOf('@');
                if (atIdx >= 0) {
                    String afterAt = baseUrl.substring(atIdx + 1);
                    if (afterAt.startsWith("//")) {
                        afterAt = afterAt.substring(2);
                    }
                    // host:port ?ì„œ port ì¶”ì¶œ
                    int colonIdx = afterAt.indexOf(':');
                    if (colonIdx >= 0) {
                        String afterColon = afterAt.substring(colonIdx + 1);
                        // port ?¤ì˜ / ?ëŠ” : (SID êµ¬ë¶„?? ?œê±°
                        int endIdx = afterColon.indexOf('/');
                        int endIdx2 = afterColon.indexOf(':');
                        if (endIdx < 0) endIdx = afterColon.length();
                        if (endIdx2 >= 0 && endIdx2 < endIdx) endIdx = endIdx2;
                        return Integer.parseInt(afterColon.substring(0, endIdx));
                    }
                }
                return 1521; // Oracle ê¸°ë³¸ ?¬íŠ¸
            }

            // ê¸°ë³¸ (MySQL, PostgreSQL, MSSQL ??
            int start = baseUrl.indexOf("://") + 3;
            if (start < 3) {
                return getDefaultPort(dbVendor);
            }
            int colonIndex = baseUrl.indexOf(":", start);
            if (colonIndex < 0) {
                return getDefaultPort(dbVendor);
            }
            String afterColon = baseUrl.substring(colonIndex + 1);
            // ?¬íŠ¸ ??êµ¬ë¶„?? / (MySQL, PostgreSQL) ?ëŠ” ; (MSSQL) ?ëŠ” \ (MSSQL named instance)
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
     * DB ë²¤ë”ë³?ê¸°ë³¸ ?¬íŠ¸ ë°˜í™˜
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
     * Oracle JDBC URL?ì„œ ?œë¹„?¤ëª…/SID ì¶”ì¶œ (database ?€ì²´ê°’)
     *
     * ì§€???•ì‹:
     * - jdbc:dadp:oracle:thin:@//host:1521/serviceName ??serviceName
     * - jdbc:dadp:oracle:thin:@host:1521:SID ??SID
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

            // @//host:1521/serviceName ?•ì‹
            if (afterAt.startsWith("//")) {
                int lastSlash = afterAt.lastIndexOf('/');
                if (lastSlash > 1) {
                    return afterAt.substring(lastSlash + 1);
                }
            }

            // @host:1521:SID ?•ì‹
            int lastColon = afterAt.lastIndexOf(':');
            if (lastColon > 0) {
                String candidate = afterAt.substring(lastColon + 1);
                // ?¬íŠ¸ ë²ˆí˜¸ê°€ ?„ë‹Œì§€ ?•ì¸
                try {
                    Integer.parseInt(candidate);
                    return null; // ?«ìë©??¬íŠ¸?´ë?ë¡?SIDê°€ ?„ë‹˜
                } catch (NumberFormatException e) {
                    return candidate; // ?«ìê°€ ?„ë‹ˆë©?SID
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * DB ë²¤ë”ëª??•ê·œ??(Hubê°€ ê¸°ë??˜ëŠ” ?•ì‹?¼ë¡œ ë³€??
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
        return lower; // ?????†ëŠ” ê²½ìš° ?ë³¸ ë°˜í™˜
    }
    
    /**
     * DB ë²¤ë”ë³?schemaName ì¶”ì¶œ
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
    
    // Getter ë©”ì„œ?œë“¤
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
        // HubIdManager?ì„œ ?„ì—­?¼ë¡œ ê´€ë¦¬ë˜??hubId ë°˜í™˜
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
    
    /** instanceId??1ê°?ê³µìœ , ì»¤ë„¥???€?ì„œ ?¬ì‚¬??*/
    public HubNotificationService getNotificationService() {
        return notificationService;
    }

    /**
     * ?¤í‚¤ë§?ê°•ì œ ë¦¬ë¡œ???˜í–‰
     *
     * Hub?ì„œ forceSchemaReload=trueë¥??˜ì‹ ??ê²½ìš° ?¸ì¶œ.
     * ?¤ì´?°ë¸Œ JDBC URLë¡?Connection???ì„±?˜ì—¬ ?¤í‚¤ë§ˆë? ?¬ìˆ˜ì§‘í•˜ê³?Hub???„ì†¡.
     */
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
            // ?¤ì´?°ë¸Œ ?œë¼?´ë²„ë¡?ì§ì ‘ Connection ?ì„± (Wrapper ?„ë¡???°íšŒ)
            if (nativeJdbcProperties != null && !nativeJdbcProperties.isEmpty()) {
                connection = java.sql.DriverManager.getConnection(nativeJdbcUrl, nativeJdbcProperties);
            } else {
                connection = java.sql.DriverManager.getConnection(nativeJdbcUrl);
            }

            // ?¤í‚¤ë§??¬ìˆ˜ì§?
            List<SchemaMetadata> reloadedSchemas = schemaSyncService.collectSchemasWithRetry(connection, 3, 2000);
            if (reloadedSchemas == null || reloadedSchemas.isEmpty()) {
                log.warn("Schema force reload: no schemas collected");
                return;
            }

            log.info("Schema force reload: collected {} schemas", reloadedSchemas.size());

            // datasourceId ?¤ì •
            if (cachedDatasourceId != null) {
                for (SchemaMetadata schema : reloadedSchemas) {
                    if (schema != null && (schema.getDatasourceId() == null || schema.getDatasourceId().trim().isEmpty())) {
                        schema.setDatasourceId(cachedDatasourceId);
                    }
                }
            }

            // ?êµ¬?€?¥ì†Œ ?…ë°?´íŠ¸
            saveSchemasToStorage(reloadedSchemas);

            // Hub???„ì†¡ (ëª¨ë“  ?¤í‚¤ë§ˆë? ê°•ì œ ?„ì†¡)
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


