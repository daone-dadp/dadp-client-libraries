package com.dadp.jdbc.config;

import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.HubIdManager;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads exported configuration file from Hub for offline bootstrap.
 *
 * Checks for an exported config file and applies it in two scenarios:
 * 1. Initial bootstrap: no existing hubId (first-time offline setup)
 * 2. Policy update: exported config has higher policyVersion than current
 *
 * File lookup priority:
 * 1. {storageDir}/exported-config.json
 * 2. {storageDir}/wrapper-config*.json (Hub에서 다운로드한 파일명 그대로 사용 가능)
 *
 * The exported config JSON format:
 * <pre>
 * {
 *   "exportVersion": 1,
 *   "hubId": "pi_xxxxxxxxxxxx",
 *   "instanceId": "soe-app-daone2",
 *   "datasourceId": "ds_xxxxxxxxxxxx",
 *   "failOpen": true,
 *   "cryptoUrl": "http://engine:9003",
 *   "hubUrl": "http://192.168.0.21:9004",
 *   "policyVersion": 8,
 *   "mappings": { "ds_xxx:schema.table.column": "policy-name" },
 *   "policyAttributes": { "policy-name": { "useIv": true, "usePlain": false } },
 *   "statsConfig": { ... }
 * }
 * </pre>
 *
 * The exported `hubUrl` must be the Hub base URL without `/hub`.
 * Wrapper components append `/hub/api/...` internally when calling Hub APIs.
 *
 * @author DADP Development Team
 * @version 5.5.8
 * @since 2026-03-09
 */
public class ExportedConfigLoader {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(ExportedConfigLoader.class);
    private static final String EXPORTED_CONFIG_FILE = "exported-config.json";
    private static final String WRAPPER_CONFIG_PREFIX = "wrapper-config";
    private static final String JSON_SUFFIX = ".json";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Try to load exported config from the storage directory.
     * Used for both initial bootstrap (hubId == null) and policy update (newer policyVersion).
     *
     * @param storageDir storage directory path (e.g., ./dadp/wrapper/{instanceId})
     * @param instanceId instance identifier
     * @param hubIdManager HubIdManager to save the hubId
     * @param policyResolver PolicyResolver to refresh policy mappings
     * @param endpointStorage EndpointStorage to save endpoint info
     * @return the loaded datasourceId if config was loaded and applied successfully, null otherwise
     */
    @SuppressWarnings("unchecked")
    public static String loadIfExists(
            String storageDir,
            String instanceId,
            HubIdManager hubIdManager,
            PolicyResolver policyResolver,
            EndpointStorage endpointStorage) {
        return loadIfExists(storageDir, instanceId, hubIdManager, policyResolver, endpointStorage, null);
    }

    /**
     * Try to load exported config (with ProxyConfig for wrapperEnabled support).
     */
    @SuppressWarnings("unchecked")
    public static String loadIfExists(
            String storageDir,
            String instanceId,
            HubIdManager hubIdManager,
            PolicyResolver policyResolver,
            EndpointStorage endpointStorage,
            ProxyConfig proxyConfig) {

        // 1. Find config file: exported-config.json first, then wrapper-config*.json
        File configFile = findConfigFile(storageDir);
        if (configFile == null) {
            log.debug("No exported config file found in: {}", storageDir);
            return null;
        }

        log.info("Exported config file found: {}", configFile.getAbsolutePath());

        try {
            // 2. Parse JSON
            Map<String, Object> config = objectMapper.readValue(configFile, Map.class);
            if (config == null || config.isEmpty()) {
                log.warn("Exported config file is empty: {}", configFile.getAbsolutePath());
                return null;
            }

            // Validate export version
            Object exportVersionObj = config.get("exportVersion");
            int exportVersion = exportVersionObj instanceof Number ? ((Number) exportVersionObj).intValue() : 0;
            if (exportVersion < 1) {
                log.warn("Exported config has invalid exportVersion: {}", exportVersion);
                return null;
            }

            // Validate required fields
            String hubId = (String) config.get("hubId");
            String datasourceId = (String) config.get("datasourceId");
            String cryptoUrl = (String) config.get("cryptoUrl");

            if (hubId == null || hubId.trim().isEmpty()) {
                log.warn("Exported config missing required field: hubId");
                return null;
            }
            if (datasourceId == null || datasourceId.trim().isEmpty()) {
                log.warn("Exported config missing required field: datasourceId");
                return null;
            }
            if (cryptoUrl == null || cryptoUrl.trim().isEmpty()) {
                log.warn("Exported config missing required field: cryptoUrl");
                return null;
            }

            // Validate instanceId matches (if present in exported config)
            String exportedInstanceId = (String) config.get("instanceId");
            if (exportedInstanceId != null && !exportedInstanceId.trim().isEmpty()
                    && !exportedInstanceId.equals(instanceId)) {
                log.warn("Exported config instanceId mismatch: exported={}, current={}",
                        exportedInstanceId, instanceId);
                return null;
            }

            applyWrapperConfig(config, proxyConfig);
            applyLogConfig(config, policyResolver);

            // Version comparison: skip if current version is same or newer
            Object policyVersionObj = config.get("policyVersion");
            Long filePolicyVersion = policyVersionObj instanceof Number
                    ? ((Number) policyVersionObj).longValue() : 0L;
            Long currentPolicyVersion = policyResolver.getCurrentVersion();

            if (currentPolicyVersion != null && currentPolicyVersion >= filePolicyVersion) {
                log.info("Exported config skipped: current policyVersion({}) >= file policyVersion({})",
                        currentPolicyVersion, filePolicyVersion);
                return null;
            }

            log.info("Exported config applying: file policyVersion({}) > current policyVersion({})",
                    filePolicyVersion, currentPolicyVersion);

            // 3. Save hubId via hubIdManager
            hubIdManager.setHubId(hubId, true);
            String wrapperAuthSecret = getStringValue(config, "wrapperAuthSecret");
            String wrapperHubId = getStringValue(config, "wrapperHubId");
            if (wrapperHubId == null || wrapperHubId.trim().isEmpty()) {
                wrapperHubId = hubId;
            }
            if (wrapperAuthSecret != null && !wrapperAuthSecret.trim().isEmpty()) {
                hubIdManager.setWrapperAuthSecret(wrapperHubId, wrapperAuthSecret, true);
                log.info("Exported config: wrapper auth secret applied: hubId={}", wrapperHubId);
            }
            log.info("Exported config: hubId applied: {}", hubId);

            // 4. Save policy mappings via policyResolver.refreshMappings()
            Map<String, String> mappings = (Map<String, String>) config.get("mappings");
            if (mappings == null) {
                mappings = new HashMap<>();
            }

            // Load policy attributes if present
            Map<String, Object> rawPolicyAttributes = (Map<String, Object>) config.get("policyAttributes");
            if (rawPolicyAttributes != null && !rawPolicyAttributes.isEmpty()) {
                Map<String, PolicyResolver.PolicyAttributes> policyAttributes = new HashMap<>();
                for (Map.Entry<String, Object> entry : rawPolicyAttributes.entrySet()) {
                    if (entry.getValue() instanceof Map) {
                        Map<String, Object> attrMap = (Map<String, Object>) entry.getValue();
                        Boolean useIv = attrMap.get("useIv") instanceof Boolean
                                ? (Boolean) attrMap.get("useIv") : null;
                        Boolean usePlain = attrMap.get("usePlain") instanceof Boolean
                                ? (Boolean) attrMap.get("usePlain") : null;
                        policyAttributes.put(entry.getKey(),
                                new PolicyResolver.PolicyAttributes(useIv, usePlain));
                    }
                }
                policyResolver.refreshMappings(mappings, policyAttributes, filePolicyVersion);
                log.info("Exported config: policy mappings applied: {} mappings, {} attributes, version={}",
                        mappings.size(), policyAttributes.size(), filePolicyVersion);
            } else {
                policyResolver.refreshMappings(mappings, filePolicyVersion);
                log.info("Exported config: policy mappings applied: {} mappings, version={}",
                        mappings.size(), filePolicyVersion);
            }

            // 5. Save endpoint info via endpointStorage.saveEndpoints()
            String hubUrl = (String) config.get("hubUrl");

            // Extract stats config if present
            Map<String, Object> statsConfig = (Map<String, Object>) config.get("statsConfig");
            Boolean statsAggregatorEnabled = null;
            String statsAggregatorUrl = null;
            String statsAggregatorMode = null;
            Integer slowThresholdMs = null;
            Boolean includeSqlNormalized = null;

            statsAggregatorEnabled = getBooleanValue(statsConfig, "enabled");
            if (statsAggregatorEnabled == null) {
                statsAggregatorEnabled = getBooleanValue(config, "statsAggregatorEnabled");
            }

            statsAggregatorUrl = getStringValue(statsConfig, "url");
            if (statsAggregatorUrl == null) {
                statsAggregatorUrl = getStringValue(config, "statsAggregatorUrl");
            }

            statsAggregatorMode = getStringValue(statsConfig, "mode");
            if (statsAggregatorMode == null) {
                statsAggregatorMode = getStringValue(config, "statsAggregatorMode");
            }

            slowThresholdMs = getIntegerValue(statsConfig, "slowThresholdMs");
            if (slowThresholdMs == null) {
                slowThresholdMs = getIntegerValue(config, "statsAggregatorSlowThresholdMs");
            }
            if (slowThresholdMs == null) {
                slowThresholdMs = getIntegerValue(config, "slowThresholdMs");
            }

            includeSqlNormalized = getBooleanValue(statsConfig, "includeSqlNormalized");
            if (includeSqlNormalized == null) {
                includeSqlNormalized = getBooleanValue(config, "statsAggregatorIncludeSqlNormalized");
            }
            if (includeSqlNormalized == null) {
                includeSqlNormalized = getBooleanValue(config, "includeSqlNormalized");
            }

            endpointStorage.saveEndpoints(cryptoUrl, hubId, filePolicyVersion,
                    statsAggregatorEnabled, statsAggregatorUrl, statsAggregatorMode,
                    slowThresholdMs, includeSqlNormalized);
            log.info("Exported config: endpoint info applied: cryptoUrl={}", cryptoUrl);

            // 6. Save datasourceId via DatasourceStorage (requires DB metadata, skip if not available)
            // The datasourceId is returned and the caller (JdbcBootstrapOrchestrator) handles caching
            log.info("Exported config: datasourceId={}", datasourceId);

            log.info("Exported config loaded successfully: hubId={}, datasourceId={}, cryptoUrl={}, " +
                    "policyVersion={}, mappings={}",
                    hubId, datasourceId, cryptoUrl, filePolicyVersion, mappings.size());

            return datasourceId;

        } catch (Exception e) {
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.trim().isEmpty()) {
                errorMessage = e.getClass().getSimpleName();
            }
            log.warn("Failed to load exported config: {}", errorMessage);
            return null;
        }
    }

    /**
     * Find config file in the storage directory.
     * Priority: exported-config.json > wrapper-config*.json
     */
    private static File findConfigFile(String storageDir) {
        File dir = new File(storageDir);

        // Priority 1: exported-config.json
        File exportedConfig = new File(dir, EXPORTED_CONFIG_FILE);
        if (exportedConfig.exists()) {
            return exportedConfig;
        }

        // Priority 2: wrapper-config*.json
        if (dir.exists() && dir.isDirectory()) {
            File[] candidates = dir.listFiles((d, name) ->
                    name.startsWith(WRAPPER_CONFIG_PREFIX) && name.endsWith(JSON_SUFFIX));
            if (candidates != null && candidates.length > 0) {
                if (candidates.length > 1) {
                    log.warn("Multiple wrapper-config files found, using: {}", candidates[0].getName());
                }
                return candidates[0];
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Boolean getBooleanValue(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static String getStringValue(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    private static Integer getIntegerValue(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static void applyWrapperConfig(Map<String, Object> config, ProxyConfig proxyConfig) {
        if (proxyConfig == null || config == null) {
            return;
        }

        Map<String, Object> wrapperConfig = (Map<String, Object>) config.get("wrapperConfig");
        Boolean enabled = getBooleanValue(wrapperConfig, "enabled");
        if (enabled == null) {
            return;
        }

        proxyConfig.setEnabled(enabled);
        log.info("Exported config: Wrapper {} by Hub config",
                enabled ? "ENABLED" : "DISABLED (passthrough mode)");
    }

    @SuppressWarnings("unchecked")
    private static void applyLogConfig(Map<String, Object> config, PolicyResolver policyResolver) {
        if (config == null || policyResolver == null) {
            return;
        }

        Map<String, Object> logConfig = (Map<String, Object>) config.get("logConfig");
        if (logConfig == null) {
            return;
        }

        Boolean enabled = getBooleanValue(logConfig, "enabled");
        String level = getStringValue(logConfig, "level");
        if (enabled == null && level == null) {
            return;
        }

        if (enabled != null) {
            DadpLoggerFactory.setFromHub(enabled, level);
            policyResolver.updateStoredLogConfig(enabled, level);
            log.info("Exported config: log config applied: enabled={}, level={}", enabled, level);
            return;
        }

        boolean currentEnabled = DadpLoggerFactory.isLoggingEnabled();
        DadpLoggerFactory.setFromHub(currentEnabled, level);
        policyResolver.updateStoredLogConfig(currentEnabled, level);
        log.info("Exported config: log level applied: level={}", level);
    }
}
