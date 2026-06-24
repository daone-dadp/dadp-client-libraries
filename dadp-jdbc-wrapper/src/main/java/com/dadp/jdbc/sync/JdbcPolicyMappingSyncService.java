package com.dadp.jdbc.sync;

import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.jdbc.schema.JdbcSchemaSyncService;
import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.WrapperRuntimeConfigManager;
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

/**
 * Applies wrapper runtime data from Hub snapshots and local runtime files.
 *
 * <p>The wrapper does not poll Hub by itself. The CLI owns Hub refresh calls
 * and writes runtime files. The runtime watcher applies those files to a live
 * JVM without an application restart.</p>
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
    
    
    private final WrapperRuntimeConfigManager tenantIdManager;
    
    
    private final PolicyMappingSyncOrchestrator syncOrchestrator;
    
    
    private volatile boolean initialized = false;
    private final String instanceId;
    
    public JdbcPolicyMappingSyncService(
            MappingSyncService mappingSyncService,
            EndpointSyncService endpointSyncService,
            JdbcSchemaSyncService jdbcSchemaSyncService,
            PolicyResolver policyResolver,
            DirectCryptoAdapter directCryptoAdapter,
            EndpointStorage endpointStorage,
            ProxyConfig config,
            InstanceConfigStorage configStorage,
            SchemaStorage schemaStorage) {
        this(mappingSyncService, endpointSyncService, jdbcSchemaSyncService, policyResolver,
                directCryptoAdapter, endpointStorage, config, configStorage, schemaStorage, null);
    }

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
            WrapperRuntimeConfigManager sharedRuntimeConfigManager) {
        this.mappingSyncService = mappingSyncService;
        this.endpointSyncService = endpointSyncService;
        this.jdbcSchemaSyncService = jdbcSchemaSyncService;
        this.policyResolver = policyResolver;
        this.directCryptoAdapter = directCryptoAdapter;
        this.endpointStorage = endpointStorage;
        this.config = config;
        this.configStorage = configStorage;
        this.schemaStorage = schemaStorage;
        
        
        InstanceIdProvider instanceIdProvider =
                new InstanceIdProvider(java.util.Collections.singletonMap("alias", config.getAlias()));
        this.instanceId = instanceIdProvider.getInstanceId();
        
        
        String hubUrl = runtimeHubUrl();
        final JdbcPolicyMappingSyncService self = this;
        if (sharedRuntimeConfigManager != null) {
            this.tenantIdManager = sharedRuntimeConfigManager;
        } else {
            this.tenantIdManager = new WrapperRuntimeConfigManager(
                configStorage,
                hubUrl,
                instanceIdProvider,
                (oldTenantId, newTenantId) -> {

                    if (newTenantId != null && !newTenantId.trim().isEmpty()) {
                        self.updateMappingSyncService(newTenantId, instanceId);
                        self.updateEndpointSyncService(newTenantId, instanceId);

                        if (self.syncOrchestrator != null) {
                            self.syncOrchestrator.updateMappingSyncService(self.mappingSyncService);
                        }
                    }
                }
            );
        }
        this.tenantIdManager.loadFromStorage();
        
        
        this.syncOrchestrator = new PolicyMappingSyncOrchestrator(
            tenantIdManager,
            mappingSyncService,
            policyResolver,
            schemaStorage,
            new PolicyMappingSyncOrchestrator.SyncCallbacks() {
                @Override
                public void onEndpointSynced(Object endpointData) {
                    
                    
                    if (endpointData instanceof MappingSyncService.EndpointInfo) {
                        MappingSyncService.EndpointInfo endpointInfo = (MappingSyncService.EndpointInfo) endpointData;
                        
                        saveEndpointFromPolicyMapping(endpointInfo);
                    } else {
                        
                        syncEndpointsAfterPolicyMapping();
                    }
                    
                    applyLogConfigFromSnapshot(self.mappingSyncService.getLastSnapshot());
                    // Hub PolicySnapshot wrapperConfig is applied whenever mappings are refreshed.
                    applyWrapperConfigFromSnapshot(self.mappingSyncService.getLastSnapshot());
                }

                @Override
                public void onSchemaReloadRequested() {
                    log.info("Schema reload requested by Hub but ignored in DADP 6.0 runtime. Run the CLI/manual schema-sync flow.");
                }
            }
        );
    }
    
    
    public void setInitialized(boolean initialized, String tenantId) {
        this.initialized = initialized;
        
        
        if (tenantId != null && !tenantId.trim().isEmpty()) {
            tenantIdManager.setWrapperEnrollment(tenantId, null, false);
        }
        
        log.info("JdbcPolicyMappingSyncService initialization notification: initialized={}, tenantId={}", initialized, tenantId);
        
        
        
        if (!initialized || tenantId == null || tenantId.trim().isEmpty()) {
            log.warn("Initialization conditions not met: initialized={}, tenantId={}", initialized, tenantId);
            return;
        }
        
        setEnabled(true);
        log.info("Wrapper runtime initialized: tenantId={}, alias={}. Hub refresh is manual via CLI only.",
                tenantId, instanceId);
    }
    
    
    public void updateEndpointSyncService(String tenantId, String instanceId) {
        
        this.endpointSyncService = new EndpointSyncService(
            runtimeHubUrl(), tenantId, instanceId, endpointStorage);
        log.info("EndpointSyncService recreated: tenantId={}", tenantId);
    }

    private String runtimeHubUrl() {
        String runtimeHubUrl = tenantIdManager != null ? tenantIdManager.getRuntimeHubUrl() : null;
        return runtimeHubUrl != null && !runtimeHubUrl.trim().isEmpty()
                ? runtimeHubUrl
                : config.getHubUrl();
    }
    
    
    private void updateMappingSyncService(String tenantId, String instanceId) {
        String hubUrl = runtimeHubUrl();
        String apiBasePath = "/hub/api/v1/runtime/wrappers";
        this.mappingSyncService = new MappingSyncService(
            hubUrl,
            tenantId,
            instanceId,
            apiBasePath,
            policyResolver);
        log.info("MappingSyncService recreated: tenantId={}", tenantId);
    }
    
    public void refreshNow() {
        refreshFromHub("manual");
    }

    public void reloadFromLocalStorage(String trigger) {
        try {
            tenantIdManager.loadFromStorage();
            policyResolver.reloadFromStorage();
            Long loadedVersion = policyResolver.getCurrentVersion();
            updateSchemaPolicyNames();
            clearLocalRuntimeRefreshCache(trigger);

            EndpointStorage.EndpointData endpointData = endpointStorage.loadEndpoints();
            if (endpointData != null && directCryptoAdapter != null) {
                directCryptoAdapter.setEndpointData(endpointData);
            }

            PolicyResolver.StoredLogConfig logConfig = policyResolver.getStoredLogConfig();
            if (logConfig != null && logConfig.getEnabled() != null) {
                DadpLoggerFactory.setFromHub(logConfig.getEnabled(), logConfig.getLevel());
            }

            if (directCryptoAdapter != null) {
                directCryptoAdapter.setFailOpen(tenantIdManager.isFailOpen());
                directCryptoAdapter.setCryptoMode(
                        tenantIdManager.getCryptoMode(),
                        runtimeHubUrl(),
                        config.isCryptoLocalFallbackRemote(),
                        config.getCryptoLocalTimeoutMs(),
                        tenantIdManager.getCachedTenantId(),
                        config.isWrapperCryptoStatsEnabled(),
                        config.getWrapperCryptoStatsAggregationLevel());
            }
            log.info("Wrapper runtime files applied: trigger={}, alias={}, tenantId={}, version={}",
                    trigger, instanceId, tenantIdManager.getCachedTenantId(), loadedVersion);
        } catch (Exception e) {
            log.warn("Wrapper runtime file apply failed: trigger={}, error={}", trigger, e.getMessage());
        }
    }

    private void refreshFromHub(String trigger) {
        if (!initialized) {
            log.warn("Policy mapping refresh skipped: service not initialized, trigger={}. Run CLI wrapper schema collect and wrapper schema register first, then restart or initialize wrapper runtime.",
                    trigger);
            return;
        }
        String tenantId = tenantIdManager.getCachedTenantId();
        if (!tenantIdManager.hasRuntimeEnrollment()) {
            log.warn("Policy mapping refresh skipped: wrapper enrollment is incomplete, trigger={}. Run CLI wrapper schema register or wrapper enroll first.",
                    trigger);
            return;
        }
        log.info("Policy mapping refresh starting: trigger={}, tenantId={}, alias={}", trigger, tenantId, instanceId);
        try {
            Long currentVersion = policyResolver.getCurrentVersion();
            int loadedCount = mappingSyncService.syncPolicyMappingsAndUpdateVersion(currentVersion);
            updateSchemaPolicyNames();
            clearLocalRuntimeRefreshCache(trigger);
            applySnapshotAfterPolicyRefresh(mappingSyncService.getLastSnapshot(), loadedCount);
            log.info("Policy mapping refresh completed: trigger={}, tenantId={}, mappings={}", trigger, tenantId, loadedCount);
        } catch (Exception e) {
            log.warn("Policy mapping refresh failed: trigger={}, error={}", trigger, e.getMessage());
        }
    }

    private void applySnapshotAfterPolicyRefresh(MappingSyncService.PolicySnapshot snapshot, int loadedCount) {
        if (snapshot != null && snapshot.getEndpoint() != null) {
            saveEndpointFromPolicyMapping(snapshot.getEndpoint());
        } else if (loadedCount > 0) {
            syncEndpointsAfterPolicyMapping();
        }
        applyLogConfigFromSnapshot(snapshot);
        applyWrapperConfigFromSnapshot(snapshot);
        if (snapshot != null && Boolean.TRUE.equals(snapshot.getForceSchemaReload())) {
            log.info("Schema reload requested by Hub but ignored in DADP 6.0 runtime. Run the CLI/manual schema-sync flow.");
        }
    }

    private void updateSchemaPolicyNames() {
        if (schemaStorage == null) {
            return;
        }
        try {
            int updatedCount = schemaStorage.updatePolicyNames(policyResolver.getAllMappings());
            if (updatedCount > 0) {
                log.debug("Schema policy names updated: {} entries", updatedCount);
            }
        } catch (Exception e) {
            log.warn("Schema policy name update failed: {}", e.getMessage());
        }
    }

    private void clearLocalRuntimeRefreshCache(String trigger) {
        if (directCryptoAdapter != null && directCryptoAdapter.isLocalCryptoMode()) {
            directCryptoAdapter.clearLocalRuntimeRefreshCache();
            log.info("Wrapper local crypto cache cleared after runtime refresh: trigger={}", trigger);
        }
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
            
            Long currentVersion = policyResolver.getCurrentVersion();
            String currentVersionValue = currentVersion != null ? String.valueOf(currentVersion) : null;
            boolean saved = configStorage.saveEngineEndpoint(cryptoUrl.trim(), currentVersionValue);
            
            if (saved) {
                
                EndpointStorage.EndpointData endpointData = configStorage.loadEndpointData();
                if (endpointData != null) {
                    
                    if (directCryptoAdapter != null) {
                        directCryptoAdapter.setEndpointData(endpointData);
                    }
                    log.info("Engine endpoint from refresh saved in proxy-config.json and applied: cryptoUrl={}, tenantId={}, version={}",
                            cryptoUrl, tenantIdManager.getCachedTenantId(), currentVersion);
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
        log.debug("Skipping standalone endpoint sync in DADP 6.0; refresh response owns engine URL.");
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
     * Applies wrapper runtime options included in the Hub policy snapshot.
     *
     * <p>The policy snapshot version is not the wrapper runtime version. Runtime version is
     * owned by the wrapper refresh contract and must not be overwritten here.</p>
     *
     * @param snapshot last policy snapshot received from Hub
     */
    private void applyWrapperConfigFromSnapshot(MappingSyncService.PolicySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        MappingSyncService.WrapperConfig wrapperConfig = snapshot.getWrapperConfig();
        if (wrapperConfig == null) return;
        if (configStorage != null) {
            try {
                configStorage.saveRuntimeOptions(
                        wrapperConfig.getCryptoMode(),
                        wrapperConfig.getFailOpen(),
                        wrapperConfig.getPolicySyncAutoEnabled(),
                        null,
                        null,
                        tenantIdManager.getCachedTenantId());
                tenantIdManager.applyRefreshOptions(
                        wrapperConfig.getEnabled(),
                        wrapperConfig.getCryptoMode(),
                        wrapperConfig.getFailOpen(),
                        wrapperConfig.getPolicySyncAutoEnabled(),
                        null,
                        false);
            } catch (Exception e) {
                log.warn("Failed to persist runtime wrapper options from Hub refresh: {}", e.getMessage());
            }
        }
        if (wrapperConfig.getFailOpen() != null && directCryptoAdapter != null) {
            directCryptoAdapter.setFailOpen(wrapperConfig.getFailOpen().booleanValue());
        }
        if (wrapperConfig.getCryptoMode() != null && !wrapperConfig.getCryptoMode().trim().isEmpty()
                && directCryptoAdapter != null) {
            String cryptoMode = wrapperConfig.getCryptoMode().trim();
            directCryptoAdapter.setCryptoMode(
                    cryptoMode,
                    runtimeHubUrl(),
                    config.isCryptoLocalFallbackRemote(),
                    config.getCryptoLocalTimeoutMs(),
                    tenantIdManager.getCachedTenantId(),
                    config.isWrapperCryptoStatsEnabled(),
                    config.getWrapperCryptoStatsAggregationLevel());
            log.info("Wrapper crypto mode applied from Hub refresh: cryptoMode={}", cryptoMode);
        }
        if (wrapperConfig.getPolicySyncAutoEnabled() != null) {
            log.info("Wrapper policySyncAutoEnabled received as {}. Hub refresh remains manual via CLI only.",
                    wrapperConfig.getPolicySyncAutoEnabled());
        }
        if (wrapperConfig.getEnabled() != null && !wrapperConfig.getEnabled()) {
            if (config != null && config.isEnabled()) {
                config.setEnabled(false);
                log.info("Wrapper DISABLED by runtime config");
            }
        } else {
            if (config != null && !config.isEnabled()) {
                config.setEnabled(true);
                if (config.isRuntimeActive()) {
                    log.info("Wrapper ENABLED by runtime config");
                } else if (!config.isStartupReady()) {
                    log.warn("Wrapper enable requested by Hub but startup prerequisites are not met; keeping passthrough mode");
                }
            }
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
        // No background Hub refresh scheduler is created by the wrapper.
    }
}
