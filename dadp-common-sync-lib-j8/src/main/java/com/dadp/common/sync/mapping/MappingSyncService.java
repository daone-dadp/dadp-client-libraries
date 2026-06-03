package com.dadp.common.sync.mapping;

import com.dadp.common.sync.http.HttpClientAdapter;
import com.dadp.common.sync.http.Java8HttpClientAdapterFactory;
import com.dadp.common.sync.policy.PolicyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

/**
 * 매핑 동기화 서비스
 * 
 * Hub로부터 정책 매핑 정보를 가져와서 PolicyResolver에 저장합니다.
 * Java 버전에 따라 적절한 HTTP 클라이언트를 자동으로 선택합니다.
 * 
 * Wrapper runtime mapping refresh service.
 * 
 * @author DADP Development Team
 * @version 5.0.4
 * @since 2025-12-31
 */
public class MappingSyncService {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(MappingSyncService.class);
    
    private final String hubUrl;
    private final String tenantId;  // Hub가 발급한 tenantId (X-DADP-Tenant-Id 헤더에 사용)
    private final String alias;  // 사용자가 설정한 instanceId (별칭, 검색/표시용)
    private final String apiBasePath;
    private final String datasourceId;  // Datasource ID (재등록을 위해 필요)
    private final HttpClientAdapter httpClient;
    private final ObjectMapper objectMapper;
    private final PolicyResolver policyResolver;
    
    // 마지막으로 받은 정책 스냅샷 (엔드포인트 정보 포함)
    private volatile PolicySnapshot lastSnapshot = null;
    
    public MappingSyncService(String hubUrl, String tenantId, String alias, String datasourceId, PolicyResolver policyResolver) {
        this(hubUrl, tenantId, alias, datasourceId, "/hub/api/v1/runtime/wrappers", policyResolver);
    }
    
    public MappingSyncService(String hubUrl, String tenantId, String alias, String datasourceId, String apiBasePath, PolicyResolver policyResolver) {
        this.hubUrl = hubUrl;
        this.tenantId = tenantId;
        this.alias = alias;
        this.datasourceId = datasourceId;
        if (apiBasePath == null || apiBasePath.trim().isEmpty()) {
            this.apiBasePath = "/hub/api/v1/runtime/wrappers";
        } else {
            this.apiBasePath = apiBasePath;
        }
        // Java 버전에 따라 적절한 HTTP 클라이언트 자동 선택
        this.httpClient = Java8HttpClientAdapterFactory.create(5000, 10000);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.policyResolver = policyResolver;
    }
    
    /**
     * Hub에서 매핑 변경 여부 확인.
     * DADP 6 wrapper refresh is the only supported runtime mapping source.
     *
     * @param version Proxy가 가진 현재 매핑 버전 (null이면 미전달)
     * @return 변경사항이 있으면 true, 없으면 false
     */
    public boolean checkMappingChange(Long version) {
        return checkMappingChange(version, null);
    }
    
    /**
     * Compatibility overload. The second parameter is ignored in DADP 6; automatic
     * automatic registration is not supported.
     *
     * @param version Proxy가 가진 현재 매핑 버전 (null이면 미전달)
     * @param ignoredReregisteredTenantId ignored
     * @return 변경사항이 있으면 true, 없으면 false
     */
    public boolean checkMappingChange(Long version, String[] ignoredReregisteredTenantId) {
        if (!isRuntimeWrapper()) {
            log.warn("Unsupported mapping API base path for DADP 6 wrapper runtime: {}", apiBasePath);
            return false;
        }
        return true;
    }
    
    /**
     * Hub에서 정책 스냅샷을 가져와서 PolicyResolver에 저장
     * 
     * @param currentVersion Proxy가 가지고 있는 현재 버전 (null이면 최신 버전 반환)
     * @return 로드된 매핑 개수
     */
    public int loadPolicySnapshotFromHub(Long currentVersion) {
        if (!isRuntimeWrapper()) {
            log.warn("Unsupported policy snapshot API base path for DADP 6 wrapper runtime: {}", apiBasePath);
            return 0;
        }
        return loadRuntimeWrapperSnapshotFromHub();
    }

    private int loadRuntimeWrapperSnapshotFromHub() {
        try {
            String canonicalRefreshUrl = resolveRuntimeRefreshUrl();
            URI uri = URI.create(canonicalRefreshUrl);
            HttpClientAdapter.HttpResponse response = httpClient.get(uri, signedHeaders("GET", uri));
            int statusCode = response.getStatusCode();

            if (statusCode == 404) {
                log.warn("Hub runtime wrapper refresh returned 404 for tenantId={}, CLI enrollment is required", tenantId);
                return -1;
            }
            if (statusCode < 200 || statusCode >= 300 || response.getBody() == null) {
                log.warn("Failed to load Hub runtime wrapper refresh: HTTP {}", statusCode);
                return 0;
            }

            PolicySnapshot snapshot = parseRuntimeWrapperSnapshot(response.getBody());
            if (Boolean.TRUE.equals(snapshot.getUnchanged())) {
                this.lastSnapshot = snapshot;
                log.debug("Hub runtime wrapper refresh unchanged: version={}", snapshot.getVersion());
                return 0;
            }
            Map<String, String> policyMap = new HashMap<>();
            List<PolicyMapping> mappings = snapshot.getMappings();
            if (mappings != null) {
                for (PolicyMapping mapping : mappings) {
                    if (mapping.isEnabled() && mapping.getPolicyName() != null
                            && !mapping.getPolicyName().trim().isEmpty()) {
                        String key = mapping.getSchemaName() + "."
                                + mapping.getTableName() + "."
                                + mapping.getColumnName();
                        policyMap.put(key, mapping.getPolicyName());
                        log.trace("Runtime policy binding loaded: {} -> {}", key, mapping.getPolicyName());
                    }
                }
            }

            Long version = snapshot.getVersion() != null ? snapshot.getVersion() : 1L;
            policyResolver.refreshMappings(policyMap, version);
            this.lastSnapshot = snapshot;
            log.info("Hub runtime wrapper refresh loaded: version={}, {} policy bindings", version, policyMap.size());
            return policyMap.size();
        } catch (IOException e) {
            log.warn("Failed to load Hub runtime wrapper refresh: {}", e.getMessage());
            policyResolver.reloadFromStorage();
            return 0;
        }
    }

    private String resolveRuntimeRefreshUrl() {
        return appendVersion(hubUrl + apiBasePath + "/" + tenantId + "/refresh");
    }

    private String appendVersion(String url) {
        Long version = policyResolver != null ? policyResolver.getCurrentVersion() : null;
        if (version == null || version <= 0) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "version=" + version;
    }

    private PolicySnapshot parseRuntimeWrapperSnapshot(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        PolicySnapshot snapshot = new PolicySnapshot();
        JsonNode unchanged = root.path("unchanged");
        if (!unchanged.isMissingNode() && !unchanged.isNull()) {
            snapshot.setUnchanged(unchanged.asBoolean(false));
        }
        JsonNode runtimeVersion = root.path("runtimeVersion");
        if (!runtimeVersion.isMissingNode() && !runtimeVersion.isNull()) {
            snapshot.setVersion(runtimeVersion.asLong());
        }
        JsonNode schemaSnapshot = root.path("schemaSnapshot");
        if (!schemaSnapshot.isMissingNode() && !schemaSnapshot.isNull()) {
            JsonNode versionNode = schemaSnapshot.path("version");
            if ((snapshot.getVersion() == null || snapshot.getVersion() <= 0)
                    && !versionNode.isMissingNode() && !versionNode.isNull()) {
                snapshot.setVersion(versionNode.asLong());
            }
            JsonNode updatedAt = schemaSnapshot.path("updatedAt");
            if (!updatedAt.isMissingNode() && !updatedAt.isNull()) {
                snapshot.setUpdatedAt(updatedAt.asText());
            }
        }

        List<PolicyMapping> mappings = new ArrayList<>();
        JsonNode bindings = root.path("policyBindings");
        if (bindings.isArray()) {
            for (JsonNode binding : bindings) {
                String status = text(binding.path("status"));
                if (status != null && !"ACTIVE".equalsIgnoreCase(status)) {
                    continue;
                }
                String policyCode = text(binding.path("policyCode"));
                if (policyCode == null || policyCode.trim().isEmpty()) {
                    continue;
                }
                PolicyMapping mapping = new PolicyMapping();
                mapping.setDatasourceId(text(binding.path("sharedDatasourceId")));
                mapping.setSchemaName(text(binding.path("schemaName")));
                mapping.setTableName(text(binding.path("tableName")));
                mapping.setColumnName(text(binding.path("columnName")));
                mapping.setPolicyName(policyCode);
                mapping.setEnabled(true);
                mappings.add(mapping);
            }
        }
        snapshot.setMappings(mappings);
        JsonNode engine = root.path("engine");
        String wrapperEngineUrl = text(engine.path("wrapperEngineUrl"));
        if (wrapperEngineUrl != null && !wrapperEngineUrl.trim().isEmpty()) {
            EndpointInfo endpointInfo = new EndpointInfo();
            endpointInfo.setCryptoUrl(wrapperEngineUrl);
            endpointInfo.setApiBasePath("/api");
            snapshot.setEndpoint(endpointInfo);
        }
        JsonNode wrapper = root.path("wrapper");
        if (!wrapper.isMissingNode() && !wrapper.isNull()) {
            WrapperConfig wrapperConfig = new WrapperConfig();
            boolean hasWrapperConfig = false;
            JsonNode enabled = wrapper.path("enabled");
            if (!enabled.isMissingNode() && !enabled.isNull()) {
                wrapperConfig.setEnabled(enabled.asBoolean());
                hasWrapperConfig = true;
            }
            String cryptoMode = text(wrapper.path("cryptoMode"));
            if (cryptoMode == null || cryptoMode.trim().isEmpty()) {
                cryptoMode = text(wrapper.path("options").path("cryptoMode"));
            }
            if (cryptoMode != null && !cryptoMode.trim().isEmpty()) {
                wrapperConfig.setCryptoMode(cryptoMode.trim());
                hasWrapperConfig = true;
            }
            JsonNode failOpen = wrapper.path("failOpen");
            if (failOpen.isMissingNode() || failOpen.isNull()) {
                failOpen = wrapper.path("options").path("failOpen");
            }
            if (!failOpen.isMissingNode() && !failOpen.isNull()) {
                wrapperConfig.setFailOpen(Boolean.valueOf(failOpen.asBoolean(false)));
                hasWrapperConfig = true;
            }
            JsonNode policySyncAutoEnabled = wrapper.path("policySyncAutoEnabled");
            if (policySyncAutoEnabled.isMissingNode() || policySyncAutoEnabled.isNull()) {
                policySyncAutoEnabled = wrapper.path("options").path("policySyncAutoEnabled");
            }
            if (!policySyncAutoEnabled.isMissingNode() && !policySyncAutoEnabled.isNull()) {
                wrapperConfig.setPolicySyncAutoEnabled(Boolean.valueOf(policySyncAutoEnabled.asBoolean(false)));
                hasWrapperConfig = true;
            }
            if (hasWrapperConfig) {
                snapshot.setWrapperConfig(wrapperConfig);
            }
            JsonNode debugEnabled = wrapper.path("debugEnabled");
            JsonNode debugLevel = wrapper.path("debugLevel");
            if (!debugEnabled.isMissingNode() || !debugLevel.isMissingNode()) {
                LogConfig logConfig = new LogConfig();
                if (!debugEnabled.isMissingNode() && !debugEnabled.isNull()) {
                    logConfig.setEnabled(debugEnabled.asBoolean());
                }
                if (!debugLevel.isMissingNode() && !debugLevel.isNull()) {
                    logConfig.setLevel(debugLevel.asText());
                }
                snapshot.setLogConfig(logConfig);
            }
        }
        return snapshot;
    }

    private boolean isRuntimeWrapper() {
        return apiBasePath != null && apiBasePath.contains("/runtime/wrappers");
    }

    private Map<String, String> signedHeaders(String method, URI uri) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("X-DADP-Tenant-Id", tenantId);
        return headers;
    }

    private static String text(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull() ? node.asText() : null;
    }

    /**
     * 마지막으로 받은 정책 스냅샷 조회 (엔드포인트 정보 포함)
     * 
     * @return 마지막 정책 스냅샷 (없으면 null)
     */
    public PolicySnapshot getLastSnapshot() {
        return lastSnapshot;
    }
    
    /**
     * Refresh DADP 6 runtime policy bindings.
     *
     * @param currentVersion current local runtime version
     * @return refreshed mapping count, or 0 when unchanged/unavailable
     */
    public int syncPolicyMappingsAndUpdateVersion(Long currentVersion) {
        try {
            int loadedCount = loadPolicySnapshotFromHub(currentVersion);
            if (loadedCount == -1) {
                log.warn("Hub runtime refresh returned 404 for tenantId. Run CLI schema-register and manual wrapper refresh.");
                return 0;
            }
            
            Long newVersion = policyResolver.getCurrentVersion();
            
            if (loadedCount > 0) {
                log.info("Policy mapping sync completed: {} mappings loaded, version={}", loadedCount, newVersion);
            } else {
                // 304 응답 시에는 아무것도 하지 않음 (현재 버전 유지)
                log.debug("No policy mapping changes or load failed");
            }
            
            return loadedCount;
        } catch (IllegalStateException e) {
            log.warn("Policy mapping sync failed: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * Hub에서 정책 매핑 정보를 가져와서 PolicyResolver에 저장 (하위 호환성)
     * 
     * @return 로드된 매핑 개수
     * @deprecated loadPolicySnapshotFromHub(Long) 사용 권장
     */
    @Deprecated
    public int loadMappingsFromHub() {
        return loadPolicySnapshotFromHub(null);
    }
    
    /**
     * 정책 스냅샷 DTO
     */
    public static class PolicySnapshot {
        private Long version;
        private String updatedAt;
        private List<PolicyMapping> mappings;
        private EndpointInfo endpoint;      // 엔드포인트 정보 (정책 매핑과 함께 받아옴)
        private LogConfig logConfig;        // 로그 설정 (Hub에서 동적으로 수신)
        private WrapperConfig wrapperConfig; // Wrapper 활성화 설정 (Hub에서 동적으로 수신)
        private Boolean unchanged;

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
        }

        public List<PolicyMapping> getMappings() {
            return mappings;
        }

        public void setMappings(List<PolicyMapping> mappings) {
            this.mappings = mappings;
        }

        public EndpointInfo getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(EndpointInfo endpoint) {
            this.endpoint = endpoint;
        }

        public LogConfig getLogConfig() {
            return logConfig;
        }

        public void setLogConfig(LogConfig logConfig) {
            this.logConfig = logConfig;
        }

        public WrapperConfig getWrapperConfig() {
            return wrapperConfig;
        }

        public void setWrapperConfig(WrapperConfig wrapperConfig) {
            this.wrapperConfig = wrapperConfig;
        }

        public Boolean getUnchanged() {
            return unchanged;
        }

        public void setUnchanged(Boolean unchanged) {
            this.unchanged = unchanged;
        }

        private Map<String, Map<String, Object>> policyAttributes;

        private Boolean forceSchemaReload;

        public Map<String, Map<String, Object>> getPolicyAttributes() {
            return policyAttributes;
        }

        public void setPolicyAttributes(Map<String, Map<String, Object>> policyAttributes) {
            this.policyAttributes = policyAttributes;
        }

        public Boolean getForceSchemaReload() {
            return forceSchemaReload;
        }

        public void setForceSchemaReload(Boolean forceSchemaReload) {
            this.forceSchemaReload = forceSchemaReload;
        }
    }

    /**
     * 로그 설정 DTO (Hub PolicySnapshot logConfig)
     */
    public static class LogConfig {
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

    /**
     * Wrapper 설정 DTO (Hub PolicySnapshot wrapperConfig)
     */
    public static class WrapperConfig {
        private Boolean enabled;
        private String cryptoMode;
        private Boolean failOpen;
        private Boolean policySyncAutoEnabled;
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public String getCryptoMode() { return cryptoMode; }
        public void setCryptoMode(String cryptoMode) { this.cryptoMode = cryptoMode; }
        public Boolean getFailOpen() { return failOpen; }
        public void setFailOpen(Boolean failOpen) { this.failOpen = failOpen; }
        public Boolean getPolicySyncAutoEnabled() { return policySyncAutoEnabled; }
        public void setPolicySyncAutoEnabled(Boolean policySyncAutoEnabled) { this.policySyncAutoEnabled = policySyncAutoEnabled; }
    }

    /**
     * 엔드포인트 정보 DTO
     */
    public static class EndpointInfo {
        private String cryptoUrl;
        private String apiBasePath;
        private StatsAggregatorInfo statsAggregator;
        
        public String getCryptoUrl() {
            return cryptoUrl;
        }
        
        public void setCryptoUrl(String cryptoUrl) {
            this.cryptoUrl = cryptoUrl;
        }
        
        public String getApiBasePath() {
            return apiBasePath;
        }
        
        public void setApiBasePath(String apiBasePath) {
            this.apiBasePath = apiBasePath;
        }

        public StatsAggregatorInfo getStatsAggregator() {
            return statsAggregator;
        }

        public void setStatsAggregator(StatsAggregatorInfo statsAggregator) {
            this.statsAggregator = statsAggregator;
        }
    }

    public static class StatsAggregatorInfo {
        private Boolean enabled;
        private String url;
        private String mode;
        private Integer slowThresholdMs;
        private Boolean includeSqlNormalized;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Integer getSlowThresholdMs() {
            return slowThresholdMs;
        }

        public void setSlowThresholdMs(Integer slowThresholdMs) {
            this.slowThresholdMs = slowThresholdMs;
        }

        public Boolean getIncludeSqlNormalized() {
            return includeSqlNormalized;
        }

        public void setIncludeSqlNormalized(Boolean includeSqlNormalized) {
            this.includeSqlNormalized = includeSqlNormalized;
        }
    }
    
    /**
     * 정책 매핑 DTO
     */
    public static class PolicyMapping {
        private String datasourceId;
        private String schemaName;
        private String tableName;
        private String columnName;
        private String policyName;
        private boolean enabled = true; // 기본값은 true (하위 호환성)
        private Boolean useIv;     // 정책 속성: IV 사용 여부 (null이면 기본값 true)
        private Boolean usePlain;  // 정책 속성: 부분암호화 여부 (null이면 기본값 false)

        public String getDatasourceId() {
            return datasourceId;
        }

        public void setDatasourceId(String datasourceId) {
            this.datasourceId = datasourceId;
        }

        public String getSchemaName() {
            return schemaName;
        }

        public void setSchemaName(String schemaName) {
            this.schemaName = schemaName;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public String getPolicyName() {
            return policyName;
        }

        public void setPolicyName(String policyName) {
            this.policyName = policyName;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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

}
