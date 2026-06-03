package com.dadp.common.sync.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
        return saveConfig(tenantId, hubUrl, instanceId, failOpen, null, null, null, null);
    }

    /**
     * Save Hub 6 runtime wrapper enrollment data issued by the CLI schema-register flow.
     */
    public boolean saveConfig(String tenantId,
                              String hubUrl,
                              String instanceId,
                              Boolean failOpen,
                              String datasourceId,
                              String refreshUrl,
                              String schemaSyncUrl,
                              String runtimeVersion) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot save instance config");
            return false;
        }
        
        try {
            ConfigData data = loadExistingConfig();
            if (data == null) {
                data = new ConfigData();
            }
            data.setTimestamp(System.currentTimeMillis());
            if (tenantId != null) {
                data.setTenantId(tenantId);
            }
            data.setHubUrl(null);
            if (instanceId != null) {
                data.setInstanceId(instanceId);
            }
            if (failOpen != null) {
                data.setFailOpen(failOpen);
            }
            if (datasourceId != null) {
                data.setDatasourceId(datasourceId);
            }
            if (refreshUrl != null) {
                data.setRefreshUrl(refreshUrl);
            }
            if (schemaSyncUrl != null) {
                data.setSchemaSyncUrl(schemaSyncUrl);
            }
            if (runtimeVersion != null) {
                data.setRuntimeVersion(runtimeVersion);
            }
            
            // 파일에 저장
            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);
            
            log.debug("Instance config saved: tenantId={}, datasourceId={}, hubUrl={}, instanceId={} -> {}",
                    data.getTenantId(), data.getDatasourceId(), data.getHubUrl(), data.getInstanceId(), storagePath);
            return true;

        } catch (IOException e) {
            log.warn("Instance config save failed: {}", storagePath, e);
            return false;
        }
    }

    /**
     * Save runtime options received from Hub refresh.
     *
     * <p>Refresh values are the first priority runtime source. They are stored so a later
     * startup can use them as the local third-priority snapshot, but they must not change
     * immutable bootstrap values such as JDBC URL hubUrl or alias.</p>
     */
    public boolean saveRuntimeOptions(Boolean wrapperEnabled, String cryptoMode, String runtimeVersion) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot save runtime options");
            return false;
        }

        try {
            ConfigData data = loadExistingConfig();
            if (data == null) {
                data = new ConfigData();
            }
            data.setTimestamp(System.currentTimeMillis());
            if (wrapperEnabled != null) {
                data.setWrapperEnabled(wrapperEnabled);
            }
            if (cryptoMode != null && !cryptoMode.trim().isEmpty()) {
                data.setCryptoMode(cryptoMode.trim());
            }
            if (runtimeVersion != null && !runtimeVersion.trim().isEmpty()) {
                data.setRuntimeVersion(runtimeVersion.trim());
            }

            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);
            log.debug("Runtime options saved: wrapperEnabled={}, cryptoMode={}, runtimeVersion={} -> {}",
                    data.getWrapperEnabled(), data.getCryptoMode(), data.getRuntimeVersion(), storagePath);
            return true;
        } catch (IOException e) {
            log.warn("Runtime option save failed: {}", storagePath, e);
            return false;
        }
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
            if (instanceId != null && !instanceId.equals(data.getInstanceId())) {
                log.debug("Instance ID mismatch, skipping config load: stored={}, requested={}",
                        data.getInstanceId(), instanceId);
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
        private String hubUrl;  // Hub URL
        private String instanceId;  // 사용자가 설정한 별칭
        private Boolean failOpen;  // Fail-open mode; Hub runtime refresh has priority.
        private String datasourceId;  // Hub-owned shared datasource ID
        private String refreshUrl;  // Hub 6 runtime refresh URL
        private String schemaSyncUrl;  // Hub 6 runtime schema-sync URL
        private String runtimeVersion;  // Hub runtime contract version
        private Boolean wrapperEnabled;  // Hub refresh runtime option
        private String cryptoMode;  // Hub refresh runtime option: remote | local
        
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
        
        public String getHubUrl() {
            return hubUrl;
        }
        
        public void setHubUrl(String hubUrl) {
            this.hubUrl = hubUrl;
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

        public String getDatasourceId() {
            return datasourceId;
        }

        public void setDatasourceId(String datasourceId) {
            this.datasourceId = datasourceId;
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

        public String getRuntimeVersion() {
            return runtimeVersion;
        }

        public void setRuntimeVersion(String runtimeVersion) {
            this.runtimeVersion = runtimeVersion;
        }

        public Boolean getWrapperEnabled() {
            return wrapperEnabled;
        }

        public void setWrapperEnabled(Boolean wrapperEnabled) {
            this.wrapperEnabled = wrapperEnabled;
        }

        public String getCryptoMode() {
            return cryptoMode;
        }

        public void setCryptoMode(String cryptoMode) {
            this.cryptoMode = cryptoMode;
        }
    }
}
