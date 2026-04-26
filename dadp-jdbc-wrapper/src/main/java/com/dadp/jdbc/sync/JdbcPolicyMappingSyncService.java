package com.dadp.jdbc.sync;

import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.jdbc.schema.JdbcSchemaSyncService;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.HubIdManager;
import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.config.InstanceIdProvider;
import com.dadp.common.sync.config.StoragePathResolver;
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
 * Periodically synchronizes policy mappings and endpoint information for the
 * JDBC wrapper.
 *
 * <p>After the wrapper is initialized, this service checks Hub for mapping
 * updates and applies refreshed endpoint and log configuration data.</p>
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
    
    
    private final HubIdManager hubIdManager;
    
    
    private final PolicyMappingSyncOrchestrator syncOrchestrator;
    
    
    private volatile boolean initialized = false;
    private final String instanceId;
    private final String datasourceId;
    
    
    private ScheduledExecutorService scheduler;
    
    
    private Runnable reregistrationCallback;

    
    private Runnable schemaReloadCallback;
    
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
        
        
        InstanceIdProvider instanceIdProvider =
                new InstanceIdProvider(java.util.Collections.singletonMap("alias", config.getAlias()));
        this.instanceId = instanceIdProvider.getInstanceId();
        
        
        String hubUrl = config.getHubUrl();
        final JdbcPolicyMappingSyncService self = this;
        this.hubIdManager = new HubIdManager(
            configStorage,
            hubUrl,
            instanceIdProvider,
            (oldHubId, newHubId) -> {
                
                if (newHubId != null && !newHubId.trim().isEmpty()) {
                    self.updateMappingSyncService(newHubId, instanceId);
                    self.updateEndpointSyncService(newHubId, instanceId);
                    
                    if (self.syncOrchestrator != null) {
                        self.syncOrchestrator.updateMappingSyncService(self.mappingSyncService);
                    }
                }
            }
        );
        
        
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
                    
                    
                    
                    log.info("Re-registration completed: hubId={} (schema re-send skipped, Hub retains by datasourceId)", newHubId);
                }
                
                @Override
                public void onEndpointSynced(Object endpointData) {
                    
                    
                    if (endpointData instanceof MappingSyncService.EndpointInfo) {
                        MappingSyncService.EndpointInfo endpointInfo = (MappingSyncService.EndpointInfo) endpointData;
                        
                        saveEndpointFromPolicyMapping(endpointInfo);
                    } else {
                        
                        syncEndpointsAfterPolicyMapping();
                    }
                    
                    applyLogConfigFromSnapshot(self.mappingSyncService.getLastSnapshot());
                    // Hub PolicySnapshot wrapperConfig 적용 (매핑 갱신 시마다 반영)
                    applyWrapperConfigFromSnapshot(self.mappingSyncService.getLastSnapshot());
                }

                @Override
                public void onSchemaReloadRequested() {
                    
                    if (schemaReloadCallback != null) {
                        log.info("Schema force reload callback invoked from Hub");
                        schemaReloadCallback.run();
                    } else {
                        log.warn("Schema force reload requested but callback not set");
                    }
                }
            }
        );
    }
    
    
    public void setInitialized(boolean initialized, String hubId) {
        this.initialized = initialized;
        
        
        if (hubId != null && !hubId.trim().isEmpty()) {
            hubIdManager.setHubId(hubId, false); 
        }
        
        log.info("JdbcPolicyMappingSyncService initialization notification: initialized={}, hubId={}", initialized, hubId);
        
        
        
        if (!initialized || hubId == null || hubId.trim().isEmpty()) {
            log.warn("Initialization conditions not met: initialized={}, hubId={}", initialized, hubId);
            return;
        }
        
        
        setEnabled(true);
        
        
        startPeriodicSync();
        
        
        
        log.info("Periodic version check started: hubId={} (first check immediately)", hubId);
    }
    
    
    private void startPeriodicSync() {
        if (scheduler != null) {
            return; 
        }
        
        log.info("Periodic policy mapping sync starting: 30s interval, alias={}, hubId={}, enabled={}, initialized={}",
                instanceId, hubIdManager.getCachedHubId(), enabled.get(), initialized);
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jdbc-policy-mapping-sync-" + instanceId);
            t.setDaemon(true);
            return t;
        });
        
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!enabled.get() || !initialized) {
                    log.debug("Periodic policy mapping sync skipped: enabled={}, initialized={}", enabled.get(), initialized);
                    return;
                }
                
                log.trace("Wrapper policy mapping version check starting");
                checkMappingChange();
            } catch (Exception e) {
                
                log.warn("Exception during periodic policy mapping version check (will retry next cycle): {}", e.getMessage(), e);
            }
        }, 0, 30, TimeUnit.SECONDS);

        log.info("Periodic policy mapping sync scheduler registered: immediate first + 30s interval, alias={}", instanceId);
    }
    
    
    public void updateEndpointSyncService(String hubId, String instanceId) {
        
        String storageDir = StoragePathResolver.resolveStorageDir(instanceId);
        String fileName = "crypto-endpoints.json";
        this.endpointSyncService = new EndpointSyncService(
            config.getHubUrl(), hubId, instanceId, storageDir, fileName);
        log.info("EndpointSyncService recreated: hubId={}", hubId);
    }
    
    
    private void updateMappingSyncService(String hubId, String instanceId) {
        String hubUrl = config.getHubUrl();
        String apiBasePath = "/hub/api/v1/proxy";  
        this.mappingSyncService = new MappingSyncService(
            hubUrl, hubId, instanceId, datasourceId, apiBasePath, policyResolver);
        log.info("MappingSyncService recreated: hubId={}", hubId);
    }
    
    
    private void checkMappingChange() {
        
        syncOrchestrator.checkMappingChange();
    }
    
    
    private void saveEndpointFromPolicyMapping(MappingSyncService.EndpointInfo endpointInfo) {
        if (endpointInfo == null) {
            log.warn("Endpoint info is null");
            return;
        }
        
        String cryptoUrl = endpointInfo.getCryptoUrl();
        if (cryptoUrl == null || cryptoUrl.trim().isEmpty()) {
            log.warn("Endpoint info missing cryptoUrl");
            return;
        }
        
        try {
            
            String currentHubId = hubIdManager.getCachedHubId();
            Long currentVersion = policyResolver.getCurrentVersion();
            MappingSyncService.StatsAggregatorInfo statsAggregator = endpointInfo.getStatsAggregator();
            Boolean statsEnabled = statsAggregator != null ? statsAggregator.getEnabled() : null;
            String statsUrl = statsAggregator != null ? statsAggregator.getUrl() : null;
            String statsMode = statsAggregator != null ? statsAggregator.getMode() : null;
            Integer slowThresholdMs = statsAggregator != null ? statsAggregator.getSlowThresholdMs() : null;
            Boolean includeSqlNormalized = statsAggregator != null ? statsAggregator.getIncludeSqlNormalized() : null;
            
            
            boolean saved = endpointStorage.saveEndpoints(
                cryptoUrl.trim(),
                currentHubId,
                currentVersion,  
                statsEnabled,
                statsUrl,
                statsMode,
                slowThresholdMs,
                includeSqlNormalized
            );
            
            if (saved) {
                
                EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
                if (endpointData != null) {
                    
                    if (directCryptoAdapter != null) {
                        directCryptoAdapter.setEndpointData(endpointData);
                    }
                    log.info("Endpoint info from policy mapping response saved and applied: cryptoUrl={}, hubId={}, version={}, statsEnabled={}, statsMode={}",
                            cryptoUrl, currentHubId, currentVersion, statsEnabled, statsMode);
                } else {
                    log.warn("Failed to load endpoint info after saving");
                }
            } else {
                log.warn("Failed to save endpoint info");
            }
        } catch (Exception e) {
            log.warn("Failed to save endpoint info: {}", e.getMessage());
        }
    }
    
    
    private void syncEndpointsAfterPolicyMapping() {
        
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
                        
                        if (directCryptoAdapter != null) {
                            directCryptoAdapter.setEndpointData(endpointData);
                        }
                        log.info("Endpoint sync completed: cryptoUrl={}, hubId={}, version={}",
                                endpointData.getCryptoUrl(),
                                endpointData.getHubId(),
                                endpointData.getVersion());
                    }
                } else {
                    log.warn("Endpoint sync failed (will retry next cycle)");
                }
            } catch (Exception e) {
                log.warn("Endpoint sync failed: {}", e.getMessage());
            }
        }
    }
    
    
    private void applyLogConfigFromSnapshot(MappingSyncService.PolicySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        MappingSyncService.LogConfig logConfig = snapshot.getLogConfig();
        if (logConfig == null) {
            return;
        }
        if (logConfig.getEnabled() != null) {
            
            String level = (logConfig.getLevel() != null && !logConfig.getLevel().trim().isEmpty())
                    ? logConfig.getLevel().trim() : null;
            DadpLoggerFactory.setFromHub(logConfig.getEnabled(), level);
            policyResolver.updateStoredLogConfig(logConfig.getEnabled(), level);
            log.info("Log config applied from Hub (1st priority): enabled={}, level={}",
                    logConfig.getEnabled(), level);
        } else if (logConfig.getLevel() != null && !logConfig.getLevel().trim().isEmpty()) {
            
            DadpLoggerFactory.setFromHub(DadpLoggerFactory.isLoggingEnabled(), logConfig.getLevel().trim());
            policyResolver.updateStoredLogConfig(DadpLoggerFactory.isLoggingEnabled(), logConfig.getLevel().trim());
            log.info("Log level applied from Hub (1st priority): level={}", logConfig.getLevel());
        }
    }


    /**
     * Hub PolicySnapshot wrapperConfig를 ProxyConfig에 반영합니다.
     * wrapperConfig가 null이거나 enabled 필드가 없으면 아무것도 하지 않습니다.
     *
     * @param snapshot 마지막으로 수신한 PolicySnapshot
     */
    private void applyWrapperConfigFromSnapshot(MappingSyncService.PolicySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        MappingSyncService.WrapperConfig wrapperConfig = snapshot.getWrapperConfig();
        if (wrapperConfig == null) return;
        if (wrapperConfig.getEnabled() != null && !wrapperConfig.getEnabled()) {
            if (config != null && config.isEnabled()) {
                config.setEnabled(false);
                log.info("Wrapper DISABLED by Hub config (30s sync)");
            }
        } else {
            if (config != null && !config.isEnabled()) {
                config.setEnabled(true);
                if (config.isRuntimeActive()) {
                    log.info("Wrapper ENABLED by Hub config (30s sync)");
                } else if (!config.isStartupReady()) {
                    log.warn("Wrapper enable requested by Hub but startup prerequisites are not met; keeping passthrough mode");
                }
            }
        }
    }

    /**
     * 재등록 콜백 설정 (JdbcBootstrapOrchestrator에서 호출)
     */
    public void setReregistrationCallback(Runnable callback) {
        this.reregistrationCallback = callback;
    }

    
    public void setSchemaReloadCallback(Runnable callback) {
        this.schemaReloadCallback = callback;
    }
    
    
    private void registerWithHub() {
        try {
            if (reregistrationCallback != null) {
                log.info("Hub re-registration starting (using stored metadata): alias={}", instanceId);
                reregistrationCallback.run();
                String hubId = hubIdManager.hasHubId() ? "hubId set" : "hubId not available";
                log.info("Hub registration completed: {} (re-registration, schema re-send skipped)", hubId);
            } else {
                log.warn("Hub re-registration needed but reregistrationCallback is not set.");
            }
        } catch (Exception e) {
            log.error("Hub re-registration failed: {}", e.getMessage(), e);
        }
    }
    
    
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        if (enabled) {
            log.info("Wrapper policy mapping sync enabled");
        } else {
            log.info("Wrapper policy mapping sync disabled");
        }
    }
    
    
    public boolean isEnabled() {
        return enabled.get();
    }
    
    
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
