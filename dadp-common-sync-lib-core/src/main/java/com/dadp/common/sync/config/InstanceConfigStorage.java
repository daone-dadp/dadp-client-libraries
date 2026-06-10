package com.dadp.common.sync.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 인스턴스 설정 영구 저장소
 * 
 * Hub에서 받은 tenantId 및 설정 정보를 파일에 저장하고,
 * 재시작 시에도 저장된 정보를 사용합니다.
 * 
 * Wrapper runtime enrollment and snapshot state storage.
 * 
 * @author DADP Development Team
 * @version 5.0.9
 * @since 2025-12-31
 */
public class InstanceConfigStorage {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(InstanceConfigStorage.class);
    
    private final String storagePath;
    private final ObjectMapper objectMapper;
    
    /**
     * 커스텀 저장 경로 지정
     * 
     * @param storageDir 저장 디렉토리
     * @param fileName 파일명
     */
    public InstanceConfigStorage(String storageDir, String fileName) {
        // 디렉토리 생성
        Path dirPath = Paths.get(storageDir);
        String finalStoragePath = null;
        try {
            Files.createDirectories(dirPath);
            finalStoragePath = Paths.get(storageDir, fileName).toString();
        } catch (IOException e) {
            log.warn("Failed to create storage directory: {} (storage unavailable)", storageDir, e);
            finalStoragePath = null; // 저장 불가
        }
        
        this.storagePath = finalStoragePath;
        this.objectMapper = new ObjectMapper();
        if (finalStoragePath != null) {
            // Connection Pool에서 반복적으로 생성되므로 TRACE 레벨로 처리 (로그 정책 참조)
            log.trace("Instance config storage initialized: {}", this.storagePath);
        } else {
            log.warn("Instance config storage initialization failed: storage unavailable");
        }
    }
    
    /**
     * 인스턴스 설정 저장
     * 
     * @param tenantId Hub가 발급한 고유 ID
     * @param hubUrl Hub URL. DADP 6 wrapper keeps hubUrl as a JDBC URL-only input; this value is accepted
     *               for legacy callers but is not persisted as runtime configuration.
     * @param instanceId 사용자가 설정한 별칭
     * @param failOpen Fail-open mode. DADP 6 runtime refresh is the final source for this option.
     * @return 저장 성공 여부
     */
    public boolean saveConfig(String tenantId, String hubUrl, String instanceId, Boolean failOpen) {
        return saveConfig(tenantId, hubUrl, instanceId, failOpen, null);
    }

    /**
     * Save Hub 6 runtime wrapper enrollment data issued by the CLI wrapper schema register flow.
     */
    public boolean saveConfig(String tenantId,
                              String hubUrl,
                              String instanceId,
                              Boolean failOpen,
                              String runtimeVersion) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot save instance config");
            return false;
        }
        
        try {
            ObjectNode data = loadExistingObjectNode();
            data.put("timestamp", System.currentTimeMillis());
            if (tenantId != null) {
                data.put("tenantId", tenantId);
            }
            removeNonPersistentBootstrapFields(data);
            if (failOpen != null) {
                data.put("failOpen", failOpen.booleanValue());
            }
            if (runtimeVersion != null) {
                data.put("runtimeVersion", runtimeVersion);
            }
            
            // 파일에 저장
            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);
            
            log.debug("Instance config saved: tenantId={}, hubUrl={}, instanceId={} -> {}",
                    text(data.path("tenantId")), text(data.path("hubUrl")), text(data.path("instanceId")), storagePath);
            return true;

        } catch (IOException e) {
            log.warn("Instance config save failed: {}", storagePath, e);
            return false;
        }
    }

    public boolean saveEnrollment(String tenantId,
                                  String alias,
                                  String runtimeVersion,
                                  String refreshUrl) {
        return saveEnrollment(tenantId, alias, runtimeVersion, null, refreshUrl, null, null);
    }

    public boolean saveEnrollment(String tenantId,
                                  String alias,
                                  String runtimeVersion,
                                  String runtimeHubUrl,
                                  String refreshUrl,
                                  String schemaSyncUrl,
                                  String engineEndpointUrl) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot save wrapper enrollment");
            return false;
        }

        try {
            ObjectNode data = loadExistingObjectNode();
            data.put("timestamp", System.currentTimeMillis());
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                data.put("tenantId", tenantId.trim());
            }
            if (alias != null && !alias.trim().isEmpty()) {
                data.put("alias", alias.trim());
            }
            if (runtimeVersion != null && !runtimeVersion.trim().isEmpty()) {
                data.put("runtimeVersion", runtimeVersion.trim());
            }
            if (refreshUrl != null && !refreshUrl.trim().isEmpty()) {
                data.put("refreshUrl", refreshUrl.trim());
            }
            saveRuntimeUrls(data, runtimeHubUrl, refreshUrl, schemaSyncUrl, engineEndpointUrl);
            removeNonPersistentBootstrapFields(data);

            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);
            log.debug("Wrapper enrollment saved: tenantId={}, alias={}, runtimeVersion={} -> {}",
                    text(data.path("tenantId")), text(data.path("alias")), text(data.path("runtimeVersion")), storagePath);
            return true;
        } catch (IOException e) {
            log.warn("Wrapper enrollment save failed: {}", storagePath, e);
            return false;
        }
    }

    /**
     * Save runtime options received from Hub refresh.
     *
     * <p>Refresh values are the first priority runtime source. They are stored so a later
     * startup can use them as the local third-priority snapshot, but they must not change
     * immutable enrollment identity such as tenantId or alias.</p>
     */
    public boolean saveRuntimeOptions(String cryptoMode,
                                      Boolean failOpen,
                                      Boolean policySyncAutoEnabled,
                                      String runtimeVersion) {
        return saveRuntimeOptions(cryptoMode, failOpen, policySyncAutoEnabled, runtimeVersion, null);
    }

    public boolean saveRuntimeOptions(String cryptoMode,
                                      Boolean failOpen,
                                      Boolean policySyncAutoEnabled,
                                      String runtimeVersion,
                                      String wrapperEngineUrl) {
        return saveRuntimeOptions(cryptoMode, failOpen, policySyncAutoEnabled, runtimeVersion, wrapperEngineUrl, null);
    }

    public boolean saveRuntimeOptions(String cryptoMode,
                                      Boolean failOpen,
                                      Boolean policySyncAutoEnabled,
                                      String runtimeVersion,
                                      String wrapperEngineUrl,
                                      String tenantId) {
        return saveRuntimeOptions(cryptoMode, failOpen, policySyncAutoEnabled,
                runtimeVersion, wrapperEngineUrl, tenantId, null, null, null, null);
    }

    public boolean saveRuntimeOptions(String cryptoMode,
                                      Boolean failOpen,
                                      Boolean policySyncAutoEnabled,
                                      String runtimeVersion,
                                      String wrapperEngineUrl,
                                      String tenantId,
                                      String runtimeHubUrl,
                                      String refreshUrl,
                                      String schemaSyncUrl,
                                      String engineEndpointUrl) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot save runtime options");
            return false;
        }

        try {
            ObjectNode data = loadExistingObjectNode();
            data.put("timestamp", System.currentTimeMillis());
            removeNonPersistentBootstrapFields(data);
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                data.put("tenantId", tenantId.trim());
            }
            if (cryptoMode != null && !cryptoMode.trim().isEmpty()) {
                data.put("cryptoMode", cryptoMode.trim());
            }
            if (failOpen != null) {
                data.put("failOpen", failOpen.booleanValue());
            }
            if (policySyncAutoEnabled != null) {
                data.put("policySyncAutoEnabled", policySyncAutoEnabled.booleanValue());
            }
            if (runtimeVersion != null && !runtimeVersion.trim().isEmpty()) {
                String normalizedRuntimeVersion = runtimeVersion.trim();
                data.put("runtimeVersion", normalizedRuntimeVersion);
                Long parsedRuntimeVersion = parseLong(normalizedRuntimeVersion);
                if (parsedRuntimeVersion != null) {
                    data.put("snapshotVersion", parsedRuntimeVersion.longValue());
                }
            }
            if (wrapperEngineUrl != null && !wrapperEngineUrl.trim().isEmpty()) {
                JsonNode existingEngine = data.path("engine");
                ObjectNode engine;
                if (existingEngine != null && existingEngine.isObject()) {
                    engine = (ObjectNode) existingEngine;
                } else {
                    engine = objectMapper.createObjectNode();
                    data.set("engine", engine);
                }
                engine.put("wrapperEngineUrl", wrapperEngineUrl.trim());
            }
            saveRuntimeUrls(data, runtimeHubUrl, refreshUrl, schemaSyncUrl,
                    firstNonBlank(engineEndpointUrl, wrapperEngineUrl));

            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);
            log.debug("Runtime options saved: cryptoMode={}, failOpen={}, policySyncAutoEnabled={}, runtimeVersion={}, wrapperEngineUrl={} -> {}",
                    text(data.path("cryptoMode")),
                    Boolean.valueOf(data.path("failOpen").asBoolean(false)),
                    data.path("policySyncAutoEnabled").isMissingNode() ? null : Boolean.valueOf(data.path("policySyncAutoEnabled").asBoolean(false)),
                    text(data.path("runtimeVersion")),
                    text(data.path("engine").path("wrapperEngineUrl")),
                    storagePath);
            return true;
        } catch (IOException e) {
            log.warn("Runtime option save failed: {}", storagePath, e);
            return false;
        }
    }

    public boolean saveEngineEndpoint(String wrapperEngineUrl, String runtimeVersion) {
        return saveRuntimeOptions(null, null, null, runtimeVersion, wrapperEngineUrl);
    }

    public EndpointStorage.EndpointData loadEndpointData() {
        ConfigData data = loadExistingConfig();
        String endpointUrl = null;
        if (data != null && data.getRuntime() != null) {
            endpointUrl = absoluteHttpUrl(data.getRuntime().getEngineEndpointUrl());
        }
        if (endpointUrl == null && data != null && data.getEngine() != null) {
            endpointUrl = absoluteHttpUrl(data.getEngine().getWrapperEngineUrl());
        }
        if (data == null || endpointUrl == null) {
            return null;
        }
        EndpointStorage.EndpointData endpointData = new EndpointStorage.EndpointData();
        endpointData.setCryptoUrl(endpointUrl);
        endpointData.setTenantId(data.getTenantId());
        endpointData.setVersion(parseLong(data.getRuntimeVersion()));
        return endpointData;
    }
    
    /**
     * 인스턴스 설정 로드
     * 
     * @param hubUrl Hub URL (일치하는 경우만 로드)
     * @param instanceId 인스턴스 ID (일치하는 경우만 로드)
     * @return 설정 데이터, 로드 실패 또는 불일치 시 null
     */
    public ConfigData loadConfig(String hubUrl, String instanceId) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot load instance config");
            return null;
        }

        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            log.debug("Instance config file not found: {} (will register with Hub)", storagePath);
            return null;
        }
        
        try {
            ConfigData data = objectMapper.readValue(storageFile, ConfigData.class);
            
            if (data == null) {
                log.warn("Instance config data is empty: {}", storagePath);
                return null;
            }
            
            // hubUrl is not a persisted runtime source in DADP 6 wrapper.
            // It must come from the JDBC URL on every startup.
            String storedAlias = firstNonBlank(data.getAlias(), data.getInstanceId());
            if (instanceId != null && storedAlias != null && !instanceId.equals(storedAlias)) {
                log.debug("Instance ID mismatch, skipping config load: stored={}, requested={}",
                        storedAlias, instanceId);
                return null;
            }
            
            long timestamp = data.getTimestamp();
            log.debug("Instance config loaded: tenantId={}, hubUrl={}, instanceId={} (saved at: {})",
                    data.getTenantId(), data.getHubUrl(), data.getInstanceId(),
                    new java.util.Date(timestamp));
            return data;
            
        } catch (IOException e) {
            log.warn("Instance config load failed: {} (returning null)", storagePath, e);
            return null;
        }
    }

    private ConfigData loadExistingConfig() {
        if (storagePath == null) {
            return null;
        }
        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            return null;
        }
        try {
            return objectMapper.readValue(storageFile, ConfigData.class);
        } catch (IOException e) {
            log.debug("Existing instance config load skipped: {}", e.getMessage());
            return null;
        }
    }

    private ObjectNode loadExistingObjectNode() {
        if (storagePath == null) {
            return objectMapper.createObjectNode();
        }
        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(storageFile);
            if (node != null && node.isObject()) {
                return (ObjectNode) node;
            }
        } catch (IOException e) {
            log.debug("Existing instance config object load skipped: {}", e.getMessage());
        }
        return objectMapper.createObjectNode();
    }

    private void removeNonPersistentBootstrapFields(ObjectNode data) {
        if (data == null) {
            return;
        }
        data.remove("hubUrl");
        data.remove("instanceId");
    }

    private void saveRuntimeUrls(ObjectNode data,
                                 String runtimeHubUrl,
                                 String refreshUrl,
                                 String schemaSyncUrl,
                                 String engineEndpointUrl) {
        String normalizedHubUrl = trimToNull(runtimeHubUrl);
        String normalizedRefreshUrl = trimToNull(refreshUrl);
        String normalizedSchemaSyncUrl = trimToNull(schemaSyncUrl);
        String normalizedEngineEndpointUrl = trimToNull(engineEndpointUrl);
        if (normalizedHubUrl == null && normalizedRefreshUrl == null
                && normalizedSchemaSyncUrl == null && normalizedEngineEndpointUrl == null) {
            return;
        }

        JsonNode existingRuntime = data.path("runtime");
        ObjectNode runtime;
        if (existingRuntime != null && existingRuntime.isObject()) {
            runtime = (ObjectNode) existingRuntime;
        } else {
            runtime = objectMapper.createObjectNode();
            data.set("runtime", runtime);
        }
        if (normalizedHubUrl != null) {
            runtime.put("hubUrl", normalizedHubUrl);
        }
        if (normalizedRefreshUrl != null) {
            runtime.put("refreshUrl", normalizedRefreshUrl);
            data.put("refreshUrl", normalizedRefreshUrl);
        }
        if (normalizedSchemaSyncUrl != null) {
            runtime.put("schemaSyncUrl", normalizedSchemaSyncUrl);
        }
        if (normalizedEngineEndpointUrl != null) {
            runtime.put("engineEndpointUrl", normalizedEngineEndpointUrl);
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value != null && !value.trim().isEmpty() ? value : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String absoluteHttpUrl(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        String lower = normalized.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://") ? normalized : null;
    }

    private static Long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = value != null ? value.trim() : null;
            if (trimmed != null && !trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }
    
    /**
     * 저장 파일 존재 여부 확인
     * 
     * @return 파일 존재 여부
     */
    public boolean hasStoredConfig() {
        if (storagePath == null) {
            return false;
        }
        return new File(storagePath).exists();
    }
    
    /**
     * 저장 파일 삭제
     * 
     * @return 삭제 성공 여부
     */
    public boolean clearStorage() {
        if (storagePath == null) {
            return false;
        }
        
        File storageFile = new File(storagePath);
        if (storageFile.exists()) {
            boolean deleted = storageFile.delete();
            if (deleted) {
                log.debug("Instance config file deleted: {}", storagePath);
            } else {
                log.warn("Instance config file deletion failed: {}", storagePath);
            }
            return deleted;
        }
        return true; // 파일이 없으면 성공으로 간주
    }
    
    /**
     * 저장 경로 조회
     * 
     * @return 저장 경로
     */
    public String getStoragePath() {
        return storagePath;
    }
    
    /**
     * 인스턴스 설정 데이터 구조
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfigData {
        private long timestamp;
        private String tenantId;  // Hub가 발급한 고유 ID
        private String alias;  // CLI schema register가 확정한 wrapper alias
        private String hubUrl;  // Hub URL
        private String refreshUrl;  // Optional canonical runtime refresh URL
        private String instanceId;  // 사용자가 설정한 별칭
        private Boolean failOpen;  // Fail-open mode; Hub runtime refresh has priority.
        private String runtimeVersion;  // Hub runtime contract version
        private String cryptoMode;  // Hub refresh runtime option: remote | local
        private Boolean policySyncAutoEnabled;  // Hub refresh runtime option
        private EngineData engine;  // Hub refresh engine endpoint data
        private RuntimeData runtime;  // CLI/runtime URL contract data
        
        public long getTimestamp() {
            return timestamp;
        }
        
        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
        
        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getAlias() {
            return alias;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }
        
        public String getHubUrl() {
            return hubUrl;
        }
        
        public void setHubUrl(String hubUrl) {
            this.hubUrl = hubUrl;
        }

        public String getRefreshUrl() {
            return refreshUrl;
        }

        public void setRefreshUrl(String refreshUrl) {
            this.refreshUrl = refreshUrl;
        }
        
        public String getInstanceId() {
            return instanceId;
        }
        
        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }
        
        public Boolean getFailOpen() {
            return failOpen;
        }
        
        public void setFailOpen(Boolean failOpen) {
            this.failOpen = failOpen;
        }

        public String getRuntimeVersion() {
            return runtimeVersion;
        }

        public void setRuntimeVersion(String runtimeVersion) {
            this.runtimeVersion = runtimeVersion;
        }

        public String getCryptoMode() {
            return cryptoMode;
        }

        public void setCryptoMode(String cryptoMode) {
            this.cryptoMode = cryptoMode;
        }

        public Boolean getPolicySyncAutoEnabled() {
            return policySyncAutoEnabled;
        }

        public void setPolicySyncAutoEnabled(Boolean policySyncAutoEnabled) {
            this.policySyncAutoEnabled = policySyncAutoEnabled;
        }

        public EngineData getEngine() {
            return engine;
        }

        public void setEngine(EngineData engine) {
            this.engine = engine;
        }

        public RuntimeData getRuntime() {
            return runtime;
        }

        public void setRuntime(RuntimeData runtime) {
            this.runtime = runtime;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EngineData {
        private String wrapperEngineUrl;

        public String getWrapperEngineUrl() {
            return wrapperEngineUrl;
        }

        public void setWrapperEngineUrl(String wrapperEngineUrl) {
            this.wrapperEngineUrl = wrapperEngineUrl;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuntimeData {
        private String hubUrl;
        private String refreshUrl;
        private String schemaSyncUrl;
        private String engineEndpointUrl;

        public String getHubUrl() {
            return hubUrl;
        }

        public void setHubUrl(String hubUrl) {
            this.hubUrl = hubUrl;
        }

        public String getRefreshUrl() {
            return refreshUrl;
        }

        public void setRefreshUrl(String refreshUrl) {
            this.refreshUrl = refreshUrl;
        }

        public String getSchemaSyncUrl() {
            return schemaSyncUrl;
        }

        public void setSchemaSyncUrl(String schemaSyncUrl) {
            this.schemaSyncUrl = schemaSyncUrl;
        }

        public String getEngineEndpointUrl() {
            return engineEndpointUrl;
        }

        public void setEngineEndpointUrl(String engineEndpointUrl) {
            this.engineEndpointUrl = engineEndpointUrl;
        }
    }
}
