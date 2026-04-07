package com.dadp.common.sync.policy;

import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.common.sync.config.StoragePathResolver;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Policy mapping persistence storage.
 */
public class PolicyMappingStorage {

    private static final DadpLogger log = DadpLoggerFactory.getLogger(PolicyMappingStorage.class);
    private static final String DEFAULT_STORAGE_FILE = "policy-mappings.json";

    private final String storagePath;
    private final ObjectMapper objectMapper;

    private static String getDefaultStorageDir() {
        return StoragePathResolver.resolveStorageDir();
    }

    private static String getDefaultStorageDir(String instanceId) {
        return StoragePathResolver.resolveStorageDir(instanceId);
    }

    public PolicyMappingStorage() {
        this(getDefaultStorageDir(), DEFAULT_STORAGE_FILE);
    }

    public PolicyMappingStorage(String instanceId) {
        this(getDefaultStorageDir(instanceId), DEFAULT_STORAGE_FILE);
    }

    public PolicyMappingStorage(String storageDir, String fileName) {
        Path dirPath = Paths.get(storageDir);
        String finalStoragePath = null;
        try {
            Files.createDirectories(dirPath);
            finalStoragePath = dirPath.resolve(fileName).toString();
        } catch (IOException e) {
            log.warn("Failed to create storage directory: {}", storageDir, e);
        }

        this.storagePath = finalStoragePath;
        this.objectMapper = new ObjectMapper();
        log.info("Policy mapping storage initialized: {}", this.storagePath);
    }

    public boolean saveMappings(Map<String, String> mappings, Long version) {
        return saveMappings(mappings, null, null, version);
    }

    public boolean saveMappings(Map<String, String> mappings,
                                Map<String, PolicyResolver.PolicyAttributes> policyAttributes,
                                Long version) {
        return saveMappings(mappings, policyAttributes, null, version);
    }

    public boolean saveMappings(Map<String, String> mappings,
                                Map<String, PolicyResolver.PolicyAttributes> policyAttributes,
                                PolicyResolver.StoredLogConfig logConfig,
                                Long version) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot save policy mappings");
            return false;
        }

        try {
            PolicyMappingData data = new PolicyMappingData();
            data.setStorageSchemaVersion(PolicyMappingData.CURRENT_STORAGE_SCHEMA_VERSION);
            data.setTimestamp(System.currentTimeMillis());
            data.setMappings(mappings);
            data.setVersion(version);
            data.setLogConfig(toLogConfigData(logConfig));

            if (policyAttributes != null && !policyAttributes.isEmpty()) {
                Map<String, PolicyAttributesData> attrDataMap = new HashMap<>();
                for (Map.Entry<String, PolicyResolver.PolicyAttributes> entry : policyAttributes.entrySet()) {
                    PolicyAttributesData attrData = new PolicyAttributesData();
                    attrData.setUseIv(entry.getValue().getUseIv());
                    attrData.setUsePlain(entry.getValue().getUsePlain());
                    attrDataMap.put(entry.getKey(), attrData);
                }
                data.setPolicyAttributes(attrDataMap);
            }

            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);

            log.debug("Policy mapping saved: {} mappings, {} attributes, logConfig={}, version={} -> {}",
                    mappings != null ? mappings.size() : 0,
                    policyAttributes != null ? policyAttributes.size() : 0,
                    logConfig != null,
                    version,
                    storagePath);
            return true;
        } catch (IOException e) {
            log.warn("Policy mapping save failed: {}", storagePath, e);
            return false;
        }
    }

    public boolean saveMappings(Map<String, String> mappings) {
        return saveMappings(mappings, null, null, null);
    }

    public Map<String, String> loadMappings() {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot load policy mappings");
            return new HashMap<>();
        }

        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            log.debug("Policy mapping storage file not found: {} (will be created)", storagePath);
            return new HashMap<>();
        }

        try {
            PolicyMappingData data = objectMapper.readValue(storageFile, PolicyMappingData.class);
            if (data == null || data.getMappings() == null) {
                log.warn("Policy mapping data is empty: {}", storagePath);
                return new HashMap<>();
            }

            int storageVersion = data.getStorageSchemaVersion();
            if (storageVersion == 0) {
                storageVersion = 1;
            }
            if (storageVersion > PolicyMappingData.CURRENT_STORAGE_SCHEMA_VERSION) {
                log.warn("Unknown policy mapping format version: {} (current supported version: {}), proceeding for backward compatibility",
                        storageVersion, PolicyMappingData.CURRENT_STORAGE_SCHEMA_VERSION);
            }

            Map<String, String> mappings = data.getMappings();
            long timestamp = data.getTimestamp();
            Long version = data.getVersion();
            log.debug("Policy mapping loaded: {} mappings, version={}, storageSchemaVersion={} (saved at: {})",
                    mappings.size(), version, storageVersion, new java.util.Date(timestamp));
            return mappings;
        } catch (IOException e) {
            log.warn("Policy mapping load failed: {} (returning empty map)", storagePath, e);
            return new HashMap<>();
        }
    }

    public Long loadVersion() {
        if (storagePath == null) {
            return null;
        }

        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            return null;
        }

        try {
            PolicyMappingData data = objectMapper.readValue(storageFile, PolicyMappingData.class);
            return data != null ? data.getVersion() : null;
        } catch (IOException e) {
            log.warn("Version info load failed: {}", storagePath, e);
            return null;
        }
    }

    public Map<String, PolicyResolver.PolicyAttributes> loadPolicyAttributes() {
        if (storagePath == null) {
            return new HashMap<>();
        }

        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            return new HashMap<>();
        }

        try {
            PolicyMappingData data = objectMapper.readValue(storageFile, PolicyMappingData.class);
            if (data == null || data.getPolicyAttributes() == null) {
                return new HashMap<>();
            }

            Map<String, PolicyResolver.PolicyAttributes> result = new HashMap<>();
            for (Map.Entry<String, PolicyAttributesData> entry : data.getPolicyAttributes().entrySet()) {
                PolicyAttributesData attrData = entry.getValue();
                PolicyResolver.PolicyAttributes attrs = new PolicyResolver.PolicyAttributes(
                        attrData.getUseIv(), attrData.getUsePlain());
                result.put(entry.getKey(), attrs);
            }

            log.debug("Policy attributes loaded: {} entries", result.size());
            return result;
        } catch (IOException e) {
            log.warn("Policy attributes load failed: {} (returning empty map)", storagePath, e);
            return new HashMap<>();
        }
    }

    public PolicyResolver.StoredLogConfig loadStoredLogConfig() {
        if (storagePath == null) {
            return null;
        }

        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            return null;
        }

        try {
            PolicyMappingData data = objectMapper.readValue(storageFile, PolicyMappingData.class);
            if (data == null || data.getLogConfig() == null) {
                return null;
            }

            LogConfigData logConfig = data.getLogConfig();
            return new PolicyResolver.StoredLogConfig(logConfig.getEnabled(), logConfig.getLevel());
        } catch (IOException e) {
            log.warn("Stored log config load failed: {}", storagePath, e);
            return null;
        }
    }

    public boolean hasStoredMappings() {
        if (storagePath == null) {
            return false;
        }
        return new File(storagePath).exists();
    }

    public boolean clearStorage() {
        if (storagePath == null) {
            return false;
        }

        File storageFile = new File(storagePath);
        if (storageFile.exists()) {
            boolean deleted = storageFile.delete();
            if (deleted) {
                log.debug("Policy mapping storage file deleted: {}", storagePath);
            } else {
                log.warn("Policy mapping storage file deletion failed: {}", storagePath);
            }
            return deleted;
        }
        return true;
    }

    public String getStoragePath() {
        return storagePath;
    }

    private LogConfigData toLogConfigData(PolicyResolver.StoredLogConfig logConfig) {
        if (logConfig == null) {
            return null;
        }
        LogConfigData data = new LogConfigData();
        data.setEnabled(logConfig.getEnabled());
        data.setLevel(logConfig.getLevel());
        return data;
    }

    public static class PolicyMappingData {
        private static final int CURRENT_STORAGE_SCHEMA_VERSION = 3;

        private int storageSchemaVersion = CURRENT_STORAGE_SCHEMA_VERSION;
        private long timestamp;
        private Map<String, String> mappings;
        private Long version;
        private Map<String, PolicyAttributesData> policyAttributes;
        private LogConfigData logConfig;

        public int getStorageSchemaVersion() {
            return storageSchemaVersion;
        }

        public void setStorageSchemaVersion(int storageSchemaVersion) {
            this.storageSchemaVersion = storageSchemaVersion;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        public Map<String, String> getMappings() {
            return mappings;
        }

        public void setMappings(Map<String, String> mappings) {
            this.mappings = mappings;
        }

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public Map<String, PolicyAttributesData> getPolicyAttributes() {
            return policyAttributes;
        }

        public void setPolicyAttributes(Map<String, PolicyAttributesData> policyAttributes) {
            this.policyAttributes = policyAttributes;
        }

        public LogConfigData getLogConfig() {
            return logConfig;
        }

        public void setLogConfig(LogConfigData logConfig) {
            this.logConfig = logConfig;
        }
    }

    public static class PolicyAttributesData {
        private Boolean useIv;
        private Boolean usePlain;

        public Boolean getUseIv() {
            return useIv;
        }

        public void setUseIv(Boolean useIv) {
            this.useIv = useIv;
        }

        public Boolean getUsePlain() {
            return usePlain;
        }

        public void setUsePlain(Boolean usePlain) {
            this.usePlain = usePlain;
        }
    }

    public static class LogConfigData {
        private Boolean enabled;
        private String level;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }
    }
}
