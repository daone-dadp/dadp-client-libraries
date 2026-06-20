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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    public static String loadTenantId(String storageDir, String expectedAlias) {
        InstanceConfigStorage.ConfigData config = loadProxyConfig(storageDir);
        if (config == null) {
            return null;
        }
        String storedAlias = trimToNull(config.getAlias());
        String normalizedExpectedAlias = trimToNull(expectedAlias);
        if (storedAlias != null && normalizedExpectedAlias != null && !storedAlias.equals(normalizedExpectedAlias)) {
            throw new IllegalStateException("Existing wrapper enrollment alias mismatch: storageAlias="
                    + storedAlias + ", requestedAlias=" + normalizedExpectedAlias);
        }
        return trimToNull(config.getTenantId());
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

    public static RuntimeContext resolveRuntimeContext(String wrapperLibDir) throws IOException {
        String storageRoot = StoragePathResolver.resolveWrapperStorageRoot(wrapperLibDir);
        Path root = Paths.get(storageRoot);
        List<RuntimeContext> contexts = new ArrayList<RuntimeContext>();

        addRuntimeContextIfPresent(contexts, root.resolve(PROXY_CONFIG_FILE));
        if (Files.isDirectory(root)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path child : stream) {
                    addRuntimeContextIfPresent(contexts, child.resolve(PROXY_CONFIG_FILE));
                }
            }
        }

        if (contexts.isEmpty()) {
            throw new IllegalStateException("Wrapper enrollment is missing under " + storageRoot
                    + ". Run wrapper schema register or wrapper enroll first.");
        }
        if (contexts.size() > 1) {
            List<String> tenants = new ArrayList<String>();
            for (RuntimeContext context : contexts) {
                tenants.add(context.getTenantId());
            }
            throw new IllegalStateException("Multiple wrapper runtime directories found under "
                    + storageRoot + ": " + tenants
                    + ". Keep one active wrapper runtime per lib directory and remove stale entries.");
        }
        return contexts.get(0);
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
        return saveEnrollment(storageDir, tenantId, alias, runtimeVersion, null, refreshUrl, null, null);
    }

    public static boolean saveEnrollment(String storageDir,
                                         String tenantId,
                                         String alias,
                                         String runtimeVersion,
                                         String runtimeHubUrl,
                                         String refreshUrl,
                                         String schemaSyncUrl,
                                         String engineEndpointUrl) {
        String normalizedTenantId = trimToNull(tenantId);
        if (normalizedTenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        InstanceConfigStorage storage = new InstanceConfigStorage(storageDir, PROXY_CONFIG_FILE);
        boolean saved = storage.saveEnrollment(normalizedTenantId, alias, runtimeVersion,
                runtimeHubUrl, refreshUrl, schemaSyncUrl, engineEndpointUrl);
        touchRefreshTrigger(storageDir);
        return saved;
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
        String cryptoMode = firstNonNull(
                text(root.path("runtime").path("cryptoMode")),
                text(wrapper.path("cryptoMode")),
                text(wrapper.path("options").path("cryptoMode")));
        Boolean failOpen = firstBoolean(
                booleanValue(root.path("runtime").path("failOpen")),
                booleanValue(wrapper.path("failOpen")),
                booleanValue(wrapper.path("options").path("failOpen")));
        Boolean policySyncAutoEnabled = firstBoolean(
                booleanValue(root.path("runtime").path("policySyncAutoEnabled")),
                booleanValue(wrapper.path("policySyncAutoEnabled")),
                booleanValue(wrapper.path("options").path("policySyncAutoEnabled")));
        String currentRuntimeHubUrl = currentConfig != null && currentConfig.getRuntime() != null
                ? firstAbsoluteHttpUrl(currentConfig.getRuntime().getHubUrl())
                : null;
        String runtimeHubUrl = firstAbsoluteHttpUrl(
                text(wrapper.path("hubUrl")),
                text(wrapper.path("options").path("hubUrl")),
                currentRuntimeHubUrl);
        if (runtimeHubUrl == null) {
            throw new IllegalStateException("wrapper.hubUrl is missing in Hub refresh response");
        }
        String engineUrl = firstAbsoluteHttpUrl(
                text(root.path("runtime").path("engineUrl")),
                text(wrapper.path("engineUrl")));

        configStorage.saveRuntimeOptions(
                cryptoMode,
                failOpen,
                policySyncAutoEnabled,
                runtimeVersion,
                engineUrl,
                tenantId,
                runtimeHubUrl,
                null,
                null,
                null);

        PolicyMappingStorage mappingStorage = new PolicyMappingStorage(storageDir, POLICY_MAPPINGS_FILE);
        Map<String, String> mappings = new LinkedHashMap<>();
        Map<String, PolicyResolver.PolicyAttributes> attributes = new HashMap<>();
        PolicyResolver.StoredLogConfig logConfig = buildStoredLogConfig(root, wrapper);
        int policyBindingCount = 0;
        JsonNode bindings = root.path("policyBindings");
        if (bindings.isArray()) {
            for (JsonNode binding : bindings) {
                policyBindingCount++;
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
                        usePlain(binding),
                        integerValue(firstPresent(binding, "plainStart", "partialPlainStart")),
                        integerValue(firstPresent(binding, "plainLength", "partialPlainLength"))));
            }
        }
        Long version = parseLong(runtimeVersion);
        mappingStorage.saveMappings(mappings, attributes, logConfig, version);
        touchRefreshTrigger(storageDir);

        return new RefreshApplyResult(version, policyBindingCount, mappings.size(), engineUrl, cryptoMode);
    }

    private static void touchRefreshTrigger(String storageDir) {
        try {
            Path dir = Paths.get(storageDir);
            Files.createDirectories(dir);
            Files.write(dir.resolve(".dadp-refresh-trigger"),
                    String.valueOf(System.currentTimeMillis()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Runtime files are already written. The watcher also observes those files.
        }
    }

    private static InstanceConfigStorage.ConfigData loadProxyConfig(String storageDir) {
        InstanceConfigStorage storage = new InstanceConfigStorage(storageDir, PROXY_CONFIG_FILE);
        return storage.loadConfig(null, null);
    }

    private static void addRuntimeContextIfPresent(List<RuntimeContext> contexts, Path proxyConfigPath) throws IOException {
        if (proxyConfigPath == null || !Files.isRegularFile(proxyConfigPath)) {
            return;
        }
        JsonNode root = OBJECT_MAPPER.readTree(proxyConfigPath.toFile());
        String tenantId = text(root.path("tenantId"));
        if (tenantId == null) {
            return;
        }
        Path storageDir = proxyConfigPath.getParent();
        contexts.add(new RuntimeContext(
                storageDir != null ? storageDir.toString() : "",
                proxyConfigPath.toString(),
                tenantId,
                text(root.path("alias")),
                text(root.path("runtimeVersion"))));
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

    private static JsonNode firstPresent(JsonNode node, String... names) {
        if (node == null || names == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        JsonNode[] nestedNodes = new JsonNode[] {
                node.path("policyMetadata"),
                node.path("metadata"),
                node.path("policy"),
                node.path("attributes")
        };
        for (JsonNode nested : nestedNodes) {
            if (nested != null && nested.isObject()) {
                JsonNode value = firstPresent(nested, names);
                if (value != null && !value.isMissingNode() && !value.isNull()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Integer integerValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return Integer.valueOf(node.asInt());
        }
        try {
            return Integer.valueOf(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
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

    private static String firstAbsoluteHttpUrl(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized == null) {
                continue;
            }
            String lower = normalized.toLowerCase();
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return normalized;
            }
        }
        return null;
    }

    private static String firstNonNull(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static Boolean firstBoolean(Boolean... values) {
        if (values == null) {
            return null;
        }
        for (Boolean value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static PolicyResolver.StoredLogConfig buildStoredLogConfig(JsonNode root, JsonNode wrapper) {
        JsonNode runtimeLogConfig = root.path("runtime").path("logConfig");
        JsonNode wrapperLogConfig = wrapper.path("logConfig");
        Boolean enabled = firstBoolean(
                booleanValue(runtimeLogConfig.path("enabled")),
                booleanValue(wrapperLogConfig.path("enabled")),
                booleanValue(wrapper.path("debugEnabled")),
                booleanValue(wrapper.path("options").path("debugEnabled")));
        String level = firstNonNull(
                text(runtimeLogConfig.path("level")),
                text(wrapperLogConfig.path("level")),
                text(wrapper.path("debugLevel")),
                text(wrapper.path("options").path("debugLevel")));
        if (enabled == null && level == null) {
            return null;
        }
        return new PolicyResolver.StoredLogConfig(enabled, level);
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
        private final int policyBindingCount;
        private final int mappingCount;
        private final String engineUrl;
        private final String cryptoMode;

        private RefreshApplyResult(Long runtimeVersion,
                                   int policyBindingCount,
                                   int mappingCount,
                                   String engineUrl,
                                   String cryptoMode) {
            this.runtimeVersion = runtimeVersion;
            this.policyBindingCount = policyBindingCount;
            this.mappingCount = mappingCount;
            this.engineUrl = engineUrl;
            this.cryptoMode = cryptoMode;
        }

        public Long getRuntimeVersion() {
            return runtimeVersion;
        }

        public int getMappingCount() {
            return mappingCount;
        }

        public int getPolicyBindingCount() {
            return policyBindingCount;
        }

        public String getEngineUrl() {
            return engineUrl;
        }

        public String getCryptoMode() {
            return cryptoMode;
        }

    }

    public static final class RuntimeContext {
        private final String storageDir;
        private final String proxyConfigPath;
        private final String tenantId;
        private final String alias;
        private final String runtimeVersion;

        private RuntimeContext(String storageDir,
                               String proxyConfigPath,
                               String tenantId,
                               String alias,
                               String runtimeVersion) {
            this.storageDir = storageDir;
            this.proxyConfigPath = proxyConfigPath;
            this.tenantId = tenantId;
            this.alias = alias;
            this.runtimeVersion = runtimeVersion;
        }

        public String getStorageDir() {
            return storageDir;
        }

        public String getProxyConfigPath() {
            return proxyConfigPath;
        }

        public String getTenantId() {
            return tenantId;
        }

        public String getAlias() {
            return alias;
        }

        public String getRuntimeVersion() {
            return runtimeVersion;
        }
    }
}
