package com.dadp.jdbc.config;

import com.dadp.common.sync.config.EndpointStorage;
import com.dadp.common.sync.config.WrapperRuntimeConfigManager;
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
 * Checks for an exported config file and applies Hub 6 wrapper enrollment only.
 *
 * File lookup priority:
 * 1. {storageDir}/exported-config.json
 * 2. {storageDir}/wrapper-config*.json (Hub에서 다운로드한 파일명 그대로 사용 가능)
 *
 * The exported config JSON format:
 * <pre>
 * {
 *   "exportVersion": 6,
 *   "tenantId": "wtenant_xxxxxxxxxxxx",
 *   "datasourceId": "ds_xxxxxxxxxxxx"
 * }
 * </pre>
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
     * Used for both initial bootstrap (tenantId == null) and policy update (newer policyVersion).
     *
     * @param storageDir storage directory path (e.g., ./dadp/wrapper/{instanceId})
     * @param instanceId instance identifier
     * @param tenantIdManager WrapperRuntimeConfigManager to save the tenantId
     * @param policyResolver PolicyResolver to refresh policy mappings
     * @param endpointStorage EndpointStorage to save endpoint info
     * @return the loaded datasourceId if config was loaded and applied successfully, null otherwise
     */
    @SuppressWarnings("unchecked")
    public static String loadIfExists(
            String storageDir,
            String instanceId,
            WrapperRuntimeConfigManager tenantIdManager,
            PolicyResolver policyResolver,
            EndpointStorage endpointStorage) {
        return loadIfExists(storageDir, instanceId, tenantIdManager, policyResolver, endpointStorage, null);
    }

    /**
     * Try to load exported config.
     */
    @SuppressWarnings("unchecked")
    public static String loadIfExists(
            String storageDir,
            String instanceId,
            WrapperRuntimeConfigManager tenantIdManager,
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

            Object exportVersionObj = config.get("exportVersion");
            int exportVersion = exportVersionObj instanceof Number ? ((Number) exportVersionObj).intValue() : 0;
            if (exportVersion < 6) {
                log.warn("Exported config has invalid exportVersion: {}", exportVersion);
                return null;
            }

            String tenantId = getStringValue(config, "tenantId");
            String datasourceId = getStringValue(config, "datasourceId");
            if (tenantId == null || tenantId.trim().isEmpty()) {
                log.warn("Exported config missing required field: tenantId");
                return null;
            }
            if (datasourceId == null || datasourceId.trim().isEmpty()) {
                log.warn("Exported config missing required field: datasourceId");
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

            tenantIdManager.setWrapperEnrollment(tenantId, datasourceId, "6.0", true);
            log.info("Exported config loaded successfully: tenantId={}, datasourceId={}",
                    tenantId, datasourceId);

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

    private static Long getLongValue(Map<String, Object> map, String key, Long defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
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
