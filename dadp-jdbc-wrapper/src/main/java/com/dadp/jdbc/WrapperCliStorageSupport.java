package com.dadp.jdbc;

import com.dadp.common.sync.config.InstanceConfigStorage;
import com.dadp.common.sync.config.StoragePathResolver;
import com.dadp.common.sync.policy.PolicyMappingStorage;
import com.dadp.common.sync.policy.PolicyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public helper methods for the external DADP CLI.
 *
 * <p>This is not a server API. The CLI may call these helpers from the wrapper
 * JAR to keep file formats aligned with wrapper runtime storage.</p>
 */
public final class WrapperCliStorageSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };
    private static final String PROXY_CONFIG_FILE = "proxy-config.json";
    private static final String POLICY_MAPPINGS_FILE = "policy-mappings.json";

    private WrapperCliStorageSupport() {
    }

    public static String loadTenantId(String storageDir) {
        InstanceConfigStorage.ConfigData config = loadProxyConfig(storageDir);
        return config != null ? trimToNull(config.getTenantId()) : null;
    }

    public static Long loadPolicyVersion(String storageDir) {
        return new PolicyMappingStorage(storageDir, POLICY_MAPPINGS_FILE).loadVersion();
    }

    public static String loadRuntimeVersion(String storageDir) {
        InstanceConfigStorage.ConfigData config = loadProxyConfig(storageDir);
        return config != null ? trimToNull(config.getRuntimeVersion()) : null;
    }

    public static String resolveStorageDir(String wrapperLibDir, String alias) {
        return StoragePathResolver.resolveStorageDirFromLibDir(alias, wrapperLibDir);
    }

    public static String resolveRuntimeStorageDir(String alias) {
        return StoragePathResolver.resolveStorageDir(alias);
    }

    public static Map<String, Object> buildSchemaRegisterPayload(File schemasJson,
                                                                  String storageDir,
                                                                  String appName,
                                                                  String wrapperVersion,
                                                                  String clientInstanceId) throws IOException {
        Map<String, Object> schemaCache = OBJECT_MAPPER.readValue(schemasJson, MAP_TYPE);
        String existingTenantId = loadTenantId(storageDir);
        return SchemaRegistrationPayloadBuilder.buildRegistrationPayload(
                schemaCache,
                existingTenantId,
                appName,
                wrapperVersion,
                clientInstanceId);
    }

    public static boolean saveEnrollment(String storageDir, String tenantId, String runtimeVersion) {
        return saveEnrollment(storageDir, tenantId, null, runtimeVersion, null);
    }

    public static boolean saveEnrollment(String storageDir,
                                         String tenantId,
                                         String alias,
                                         String runtimeVersion,
                                         String refreshUrl) {
        String normalizedTenantId = trimToNull(tenantId);
        if (normalizedTenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        InstanceConfigStorage storage = new InstanceConfigStorage(storageDir, PROXY_CONFIG_FILE);
        return storage.saveEnrollment(normalizedTenantId, alias, runtimeVersion, refreshUrl);
    }

    public static RefreshApplyResult applyRefreshResponse(String storageDir, String responseBody) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        InstanceConfigStorage configStorage = new InstanceConfigStorage(storageDir, PROXY_CONFIG_FILE);
        InstanceConfigStorage.ConfigData currentConfig = configStorage.loadConfig(null, null);
        String tenantId = currentConfig != null ? trimToNull(currentConfig.getTenantId()) : null;
        if (tenantId == null) {
            throw new IllegalStateException("tenantId is missing in proxy-config.json");
        }

        String runtimeVersion = text(root.path("runtimeVersion"));
        JsonNode wrapper = root.path("wrapper");
        String cryptoMode = text(wrapper.path("cryptoMode"));
        Boolean failOpen = booleanValue(wrapper.path("failOpen"));
        Boolean policySyncAutoEnabled = booleanValue(wrapper.path("policySyncAutoEnabled"));
        String wrapperEngineUrl = text(root.path("engine").path("wrapperEngineUrl"));

        configStorage.saveRuntimeOptions(
                cryptoMode,
                failOpen,
                policySyncAutoEnabled,
                runtimeVersion,
                wrapperEngineUrl,
                tenantId);

        PolicyMappingStorage mappingStorage = new PolicyMappingStorage(storageDir, POLICY_MAPPINGS_FILE);
        Map<String, String> mappings = new LinkedHashMap<>();
        Map<String, PolicyResolver.PolicyAttributes> attributes = new HashMap<>();
        JsonNode bindings = root.path("policyBindings");
        if (bindings.isArray()) {
            for (JsonNode binding : bindings) {
                String status = text(binding.path("status"));
                if (status != null && !"ACTIVE".equalsIgnoreCase(status)) {
                    continue;
                }
                String policyCode = text(binding.path("policyCode"));
                String tableName = text(binding.path("tableName"));
                String columnName = text(binding.path("columnName"));
                if (policyCode == null || tableName == null || columnName == null) {
                    continue;
                }
                String schemaName = text(binding.path("schemaName"));
                String key = schemaName != null
                        ? schemaName + "." + tableName + "." + columnName
                        : tableName + "." + columnName;
                mappings.put(key, policyCode);
                attributes.put(policyCode, new PolicyResolver.PolicyAttributes(
                        useIv(binding),
                        usePlain(binding)));
            }
        }
        Long version = parseLong(runtimeVersion);
        mappingStorage.saveMappings(mappings, attributes, version);

        return new RefreshApplyResult(version, mappings.size(), wrapperEngineUrl);
    }

    private static InstanceConfigStorage.ConfigData loadProxyConfig(String storageDir) {
        InstanceConfigStorage storage = new InstanceConfigStorage(storageDir, PROXY_CONFIG_FILE);
        return storage.loadConfig(null, null);
    }

    private static Boolean useIv(JsonNode binding) {
        Boolean explicitUseIv = booleanValue(binding.path("useIv"));
        if (explicitUseIv != null) {
            return explicitUseIv;
        }
        Boolean deterministic = booleanValue(binding.path("deterministic"));
        return deterministic != null ? Boolean.valueOf(!deterministic.booleanValue()) : null;
    }

    private static Boolean usePlain(JsonNode binding) {
        Boolean explicitUsePlain = booleanValue(binding.path("usePlain"));
        if (explicitUsePlain != null) {
            return explicitUsePlain;
        }
        Boolean partialEncryption = booleanValue(binding.path("partialEncryption"));
        return partialEncryption != null ? partialEncryption : null;
    }

    private static Boolean booleanValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return Boolean.valueOf(node.asBoolean());
    }

    private static Long parseLong(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Long.valueOf(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return trimToNull(node.asText());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class RefreshApplyResult {
        private final Long runtimeVersion;
        private final int mappingCount;
        private final String wrapperEngineUrl;

        private RefreshApplyResult(Long runtimeVersion, int mappingCount, String wrapperEngineUrl) {
            this.runtimeVersion = runtimeVersion;
            this.mappingCount = mappingCount;
            this.wrapperEngineUrl = wrapperEngineUrl;
        }

        public Long getRuntimeVersion() {
            return runtimeVersion;
        }

        public int getMappingCount() {
            return mappingCount;
        }

        public String getWrapperEngineUrl() {
            return wrapperEngineUrl;
        }
    }
}
