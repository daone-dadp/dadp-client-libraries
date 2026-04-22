package com.dadp.common.sync.policy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.common.sync.config.StoragePathResolver;

/**
 * Resolves policy names for schema, table, and column combinations.
 *
 * <p>The resolver keeps an in-memory cache and persists mappings so wrapper
 * components can continue operating when Hub synchronization is temporarily
 * unavailable.</p>
 */

public class PolicyResolver {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(PolicyResolver.class);
    
    
    private static volatile PolicyResolver defaultInstance = null;
    private static final Object singletonLock = new Object();
    private static final Map<String, PolicyResolver> instanceResolvers = new ConcurrentHashMap<>();
    
    
    private final Map<String, String> policyCache = new ConcurrentHashMap<>();

    
    private final Map<String, PolicyAttributes> policyAttributeCache = new ConcurrentHashMap<>();

    
    private volatile Long currentVersion = null;

    
    private volatile StoredLogConfig storedLogConfig = null;

    private volatile ProtectedColumnIndex protectedColumnIndex = null;
    
    
    private final PolicyMappingStorage storage;
    
    
    private static String getDefaultStorageDir() {
        return StoragePathResolver.resolveStorageDir();
    }
    
    private static String getDefaultStorageDir(String instanceId) {
        return StoragePathResolver.resolveStorageDir(instanceId);
    }
    
    
    public static PolicyResolver getInstance() {
        if (defaultInstance == null) {
            synchronized (singletonLock) {
                if (defaultInstance == null) {
                    defaultInstance = new PolicyResolver();
                }
            }
        }
        return defaultInstance;
    }
    
    public static PolicyResolver getInstance(String instanceId) {
        String storageDir = getDefaultStorageDir(instanceId);
        return instanceResolvers.computeIfAbsent(storageDir, dir -> new PolicyResolver(dir, "policy-mappings.json"));
    }
    
    
    public PolicyResolver() {
        this(getDefaultStorageDir(), "policy-mappings.json");
    }
    
    
    public PolicyResolver(String storageDir, String fileName) {
        this.storage = new PolicyMappingStorage(storageDir, fileName);
        
        loadMappingsFromStorage();
    }
    
    
    public PolicyResolver(PolicyMappingStorage storage) {
        this.storage = storage;
        
        loadMappingsFromStorage();
    }
    
    
    private void loadMappingsFromStorage() {
        Map<String, String> storedMappings = canonicalizeMappings(storage.loadMappings());
        if (!storedMappings.isEmpty()) {
            policyCache.putAll(storedMappings);
            
            Long storedVersion = storage.loadVersion();
            if (storedVersion != null) {
                this.currentVersion = storedVersion;
            } else {
                
                this.currentVersion = 0L;
                log.debug("No version info in persistent storage, initializing to 0");
            }
            
            Map<String, PolicyAttributes> storedAttributes = storage.loadPolicyAttributes();
            if (storedAttributes != null && !storedAttributes.isEmpty()) {
                policyAttributeCache.putAll(storedAttributes);
                log.debug("Policy attributes loaded from persistent storage: {} entries", storedAttributes.size());
            }
            this.storedLogConfig = storage.loadStoredLogConfig();
            if (this.storedLogConfig != null) {
                log.debug("Stored log config loaded from persistent storage: enabled={}, level={}",
                        this.storedLogConfig.getEnabled(), this.storedLogConfig.getLevel());
            }
            log.debug("Policy mappings loaded from persistent storage: {} mappings, version={}",
                    storedMappings.size(), this.currentVersion);
        } else {
            
            this.currentVersion = 0L;
            log.debug("No policy mappings in persistent storage (will load from Hub), initializing version=0");
        }
    }
    
    
    public String resolvePolicy(String datasourceId, String schemaName, String tableName, String columnName) {
        String key = buildSchemaOrTableKey(schemaName, tableName, columnName);

        log.trace("Policy lookup: key={}, datasourceId(ignored)={}, schemaName={}, tableName={}, columnName={}",
                key, datasourceId, schemaName, tableName, columnName);

        String policy = lookupPolicy(key);
        if (policy != null) {
            return policy;
        }

        String fallbackKey = buildTableKey(tableName, columnName);
        if (!Objects.equals(fallbackKey, key)) {
            policy = lookupPolicy(fallbackKey);
            if (policy != null) {
                return policy;
            }
        }

        return null;
    }
    
    
    @Deprecated
    public String resolvePolicy(String databaseName, String tableName, String columnName) {
        return resolvePolicy(null, databaseName != null ? databaseName : "", tableName, columnName);
    }
    
    
    @Deprecated
    public String resolvePolicy(String tableName, String columnName) {
        return resolvePolicy(null, tableName, columnName);
    }
    
    
    private String resolveByRules(String tableName, String columnName) {
        String columnLower = columnName.toLowerCase();
        
        
        if (columnLower.contains("email") || columnLower.contains("mail")) {
            return "dadp";
        }
        
        
        if (columnLower.contains("phone") || columnLower.contains("tel") || columnLower.contains("mobile")) {
            return "dadp";
        }
        
        
        if (columnLower.contains("ssn") || columnLower.contains("rrn") || columnLower.contains("resident")) {
            return "pii";
        }
        
        
        if (columnLower.contains("name") && !columnLower.contains("username")) {
            return "dadp";
        }
        
        
        if (columnLower.contains("address") || columnLower.contains("addr")) {
            return "dadp";
        }
        
        return null;
    }
    
    
    public void refreshMappings(Map<String, String> mappings, Long version) {
        log.trace("Policy mapping cache refresh started: {} mappings, version={}", mappings.size(), version);
        Map<String, String> canonicalMappings = canonicalizeMappings(mappings);
        policyCache.clear();
        policyCache.putAll(canonicalMappings);
        protectedColumnIndex = null;
        
        
        
        if (version != null) {
            this.currentVersion = version;
            log.debug("Policy version updated: version={}", version);
        } else {
            
            this.currentVersion = 0L;
            log.debug("No version info received from Hub (version=null), initializing to 0");
        }
        
        
        
        boolean saved = storage.saveMappings(canonicalMappings, null, storedLogConfig, version);
        if (saved) {
            log.debug("Policy mappings persisted: {} mappings, version={}", canonicalMappings.size(), version);
        } else {
            log.warn("Policy mappings persistence failed (using memory cache only)");
        }
        
        log.trace("Policy mapping cache refresh completed");
    }
    
    
    public void refreshMappings(Map<String, String> mappings, Map<String, PolicyAttributes> attributes, Long version) {
        refreshMappings(mappings, version);

        
        if (attributes != null && !attributes.isEmpty()) {
            policyAttributeCache.clear();
            policyAttributeCache.putAll(attributes);
            log.debug("Policy attributes cache refreshed: {} policies", attributes.size());

            
            storage.saveMappings(canonicalizeMappings(mappings), attributes, storedLogConfig, version);
        }
    }

    
    public boolean isSearchEncryptionNeeded(String policyName) {
        if (policyName == null) {
            return false;
        }
        PolicyAttributes attrs = policyAttributeCache.get(policyName);
        if (attrs == null) {
            
            return false;
        }
        boolean useIv = attrs.getUseIv() != null ? attrs.getUseIv() : true;
        boolean usePlain = attrs.getUsePlain() != null ? attrs.getUsePlain() : false;
        
        return !useIv && !usePlain;
    }

    
    @Deprecated
    public void refreshMappings(Map<String, String> mappings) {
        refreshMappings(mappings, null);
    }
    
    
    
    public Long getCurrentVersion() {
        return currentVersion;
    }
    
    
    public void setCurrentVersion(Long version) {
        this.currentVersion = version;
    }
    
    
    public void addMapping(String databaseName, String tableName, String columnName, String policyName) {
        String key = buildSchemaOrTableKey(databaseName, tableName, columnName);
        policyCache.put(key, policyName);
        log.trace("Policy mapping added: {} -> {}", key, policyName);
    }
    
    
    @Deprecated
    public void addMapping(String tableName, String columnName, String policyName) {
        addMapping(null, tableName, columnName, policyName);
    }
    
    
    public void removeMapping(String databaseName, String tableName, String columnName) {
        String key = buildSchemaOrTableKey(databaseName, tableName, columnName);
        policyCache.remove(key);
        log.trace("Policy mapping removed: {}", key);
    }
    
    
    @Deprecated
    public void removeMapping(String tableName, String columnName) {
        removeMapping(null, tableName, columnName);
    }
    
    
    public void clearCache() {
        policyCache.clear();
        protectedColumnIndex = null;
        log.trace("Policy mapping cache cleared");
    }
    
    
    public void reloadFromStorage() {
        Map<String, String> storedMappings = canonicalizeMappings(storage.loadMappings());
        if (!storedMappings.isEmpty()) {
            policyCache.clear();
            policyCache.putAll(storedMappings);
            protectedColumnIndex = null;
            Long storedVersion = storage.loadVersion();
            if (storedVersion != null) {
                this.currentVersion = storedVersion;
            }
            this.storedLogConfig = storage.loadStoredLogConfig();
            log.debug("Policy mappings reloaded from persistent storage: {} mappings, version={}",
                    storedMappings.size(), storedVersion);
        } else {
            log.warn("No policy mappings in persistent storage");
        }
    }
    
    
    public String getStoragePath() {
        return storage.getStoragePath();
    }
    
    
    public Map<String, String> getAllMappings() {
        return new HashMap<>(policyCache);
    }

    public ProtectedColumnIndex getProtectedColumnIndex() {
        Map<String, String> mappingsSnapshot = getAllMappings();
        Long versionSnapshot = currentVersion;
        int mappingCount = mappingsSnapshot.size();
        int mappingHash = mappingsSnapshot.hashCode();

        ProtectedColumnIndex local = protectedColumnIndex;
        if (local != null && local.matches(versionSnapshot, mappingCount, mappingHash)) {
            return local;
        }

        synchronized (this) {
            local = protectedColumnIndex;
            if (local != null && local.matches(versionSnapshot, mappingCount, mappingHash)) {
                return local;
            }

            ProtectedColumnIndex rebuilt = ProtectedColumnIndex.fromMappings(mappingsSnapshot, versionSnapshot);
            protectedColumnIndex = rebuilt;
            return rebuilt;
        }
    }

    public void updateStoredLogConfig(Boolean enabled, String level) {
        this.storedLogConfig = new StoredLogConfig(enabled, level);
        boolean saved = storage.saveMappings(new HashMap<>(policyCache), new HashMap<>(policyAttributeCache), this.storedLogConfig, currentVersion);
        if (saved) {
            log.debug("Stored log config persisted: enabled={}, level={}", enabled, level);
        } else {
            log.warn("Stored log config persistence failed");
        }
    }

    public StoredLogConfig getStoredLogConfig() {
        return storedLogConfig;
    }

    
    public static class PolicyAttributes {
        private Boolean useIv;
        private Boolean usePlain;

        public PolicyAttributes() {
        }

        public PolicyAttributes(Boolean useIv, Boolean usePlain) {
            this.useIv = useIv;
            this.usePlain = usePlain;
        }

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

    public static class StoredLogConfig {
        private Boolean enabled;
        private String level;

        public StoredLogConfig() {
        }

        public StoredLogConfig(Boolean enabled, String level) {
            this.enabled = enabled;
            this.level = level;
        }

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

    public static class ProtectedColumnIndex {
        private final Long policyVersion;
        private final int mappingCount;
        private final int mappingHash;
        private final Map<String, String> normalizedMappings;

        private ProtectedColumnIndex(Long policyVersion, int mappingCount, int mappingHash, Map<String, String> normalizedMappings) {
            this.policyVersion = policyVersion;
            this.mappingCount = mappingCount;
            this.mappingHash = mappingHash;
            this.normalizedMappings = normalizedMappings;
        }

        public static ProtectedColumnIndex fromMappings(Map<String, String> mappings, Long policyVersion) {
            Map<String, String> normalized = new HashMap<>();
            if (mappings != null) {
                for (Map.Entry<String, String> entry : mappings.entrySet()) {
                    String key = canonicalizeLookupKey(entry.getKey());
                    if (key != null && entry.getValue() != null) {
                        normalized.put(key, entry.getValue());
                    }
                }
            }
            return new ProtectedColumnIndex(
                    policyVersion,
                    mappings != null ? mappings.size() : 0,
                    mappings != null ? mappings.hashCode() : 0,
                    Collections.unmodifiableMap(normalized));
        }

        public String resolvePolicy(String datasourceId, String schemaName, String tableName, String columnName) {
            String normalizedSchemaName = normalizeSegment(schemaName);
            String normalizedTableName = normalizeSegment(tableName);
            String normalizedColumnName = normalizeSegment(columnName);

            if (normalizedTableName == null || normalizedColumnName == null) {
                return null;
            }

            if (normalizedSchemaName != null) {
                String schemaScopedPolicy = normalizedMappings.get(
                        buildSchemaScopedKey(normalizedSchemaName, normalizedTableName, normalizedColumnName));
                if (schemaScopedPolicy != null) {
                    return schemaScopedPolicy;
                }
            }

            return normalizedMappings.get(buildTableScopedKey(normalizedTableName, normalizedColumnName));
        }

        boolean matches(Long expectedVersion, int expectedMappingCount, int expectedMappingHash) {
            return Objects.equals(policyVersion, expectedVersion)
                    && mappingCount == expectedMappingCount
                    && mappingHash == expectedMappingHash;
        }

        private static String normalizeSegment(String value) {
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            return value.trim().toLowerCase(Locale.ROOT);
        }

        private static String buildSchemaScopedKey(String schemaName, String tableName, String columnName) {
            return schemaName + "." + tableName + "." + columnName;
        }

        private static String buildTableScopedKey(String tableName, String columnName) {
            return tableName + "." + columnName;
        }
    }

    private String lookupPolicy(String key) {
        if (key == null) {
            return null;
        }

        String policy = policyCache.get(key);
        if (policy != null) {
            log.trace("Policy cache hit: {} -> {}", key, policy);
            return policy;
        }

        String lowerKey = key.toLowerCase(Locale.ROOT);
        if (!lowerKey.equals(key)) {
            policy = policyCache.get(lowerKey);
            if (policy != null) {
                log.trace("Policy cache hit (lowercase): {} -> {}", lowerKey, policy);
                return policy;
            }
        }
        return null;
    }

    private static Map<String, String> canonicalizeMappings(Map<String, String> mappings) {
        Map<String, String> canonicalMappings = new HashMap<>();
        if (mappings == null || mappings.isEmpty()) {
            return canonicalMappings;
        }

        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String canonicalKey = canonicalizeLookupKey(entry.getKey());
            if (canonicalKey != null && entry.getValue() != null) {
                canonicalMappings.put(canonicalKey, entry.getValue());
            }
        }
        return canonicalMappings;
    }

    private static String canonicalizeLookupKey(String key) {
        if (key == null) {
            return null;
        }

        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        int datasourceSeparator = trimmed.indexOf(':');
        if (datasourceSeparator >= 0 && datasourceSeparator + 1 < trimmed.length()) {
            trimmed = trimmed.substring(datasourceSeparator + 1);
        }

        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String buildSchemaOrTableKey(String schemaName, String tableName, String columnName) {
        String normalizedSchema = normalizeLookupSegment(schemaName);
        String normalizedTable = normalizeLookupSegment(tableName);
        String normalizedColumn = normalizeLookupSegment(columnName);
        if (normalizedTable == null || normalizedColumn == null) {
            return null;
        }
        if (normalizedSchema != null) {
            return normalizedSchema + "." + normalizedTable + "." + normalizedColumn;
        }
        return buildTableKey(normalizedTable, normalizedColumn);
    }

    private static String buildTableKey(String tableName, String columnName) {
        String normalizedTable = normalizeLookupSegment(tableName);
        String normalizedColumn = normalizeLookupSegment(columnName);
        if (normalizedTable == null || normalizedColumn == null) {
            return null;
        }
        return normalizedTable + "." + normalizedColumn;
    }

    private static String normalizeLookupSegment(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
