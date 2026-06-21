package com.dadp.jdbc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Legacy proxy configuration storage.
 *
 * @deprecated Use {@link com.dadp.common.sync.config.InstanceConfigStorage}.
 * 
 * @author DADP Development Team
 * @version 4.8.1
 * @since 2025-12-16
 * @see com.dadp.common.sync.config.InstanceConfigStorage
 */
@Deprecated
public class ProxyConfigStorage {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(ProxyConfigStorage.class);
    
    private static final String DEFAULT_STORAGE_FILE = "proxy-config.json";
    
    private final String storagePath;
    private final ObjectMapper objectMapper;
    
    public ProxyConfigStorage() {
        throw new IllegalStateException("ProxyConfigStorage requires an explicit alias storage directory");
    }
    
    /**
     * Creates storage with an explicit wrapper-managed directory.
     *
     * @param storageDir wrapper-managed storage directory
     * @param fileName storage file name
     */
    public ProxyConfigStorage(String storageDir, String fileName) {
        Path dirPath = Paths.get(storageDir);
        String finalStoragePath = null;
        try {
            Files.createDirectories(dirPath);
            finalStoragePath = Paths.get(storageDir, fileName).toString();
        } catch (IOException e) {
            log.warn("Failed to create storage directory: {} (storage unavailable)", storageDir, e);
            finalStoragePath = null;
        }
        
        this.storagePath = finalStoragePath;
        this.objectMapper = new ObjectMapper();
        log.debug("Proxy config storage initialized: {}", this.storagePath);
    }
    
    /**
     * Proxy 설정 저장
     * 
     * @param tenantId Hub가 발급한 고유 ID
     * @param hubUrl Hub URL
     * @param instanceId 사용자가 설정한 별칭
     * @param failOpen Fail-open 모드 여부
     * @return 저장 성공 여부
     */
    public boolean saveConfig(String tenantId, String hubUrl, String instanceId, boolean failOpen) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot save proxy config");
            return false;
        }
        
        try {
            // 저장 데이터 구조
            ConfigData data = new ConfigData();
            data.setTimestamp(System.currentTimeMillis());
            data.setTenantId(tenantId);
            data.setHubUrl(hubUrl);
            data.setInstanceId(instanceId);
            data.setFailOpen(failOpen);
            
            // 파일에 저장
            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);
            
            log.debug("Proxy config saved: tenantId={}, hubUrl={}, instanceId={} -> {}",
                    tenantId, hubUrl, instanceId, storagePath);
            return true;
            
        } catch (IOException e) {
            log.error("Failed to save proxy config: {}", storagePath, e);
            return false;
        }
    }
    
    /**
     * Proxy 설정 로드
     * 
     * @return 설정 데이터, 로드 실패 시 null
     */
    public ConfigData loadConfig() {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot load proxy config");
            return null;
        }
        
        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            log.debug("Proxy config storage file not found: {} (will register with Hub)", storagePath);
            return null;
        }
        
        try {
            ConfigData data = objectMapper.readValue(storageFile, ConfigData.class);
            
            if (data == null) {
                log.warn("Proxy config data is empty: {}", storagePath);
                return null;
            }
            
            long timestamp = data.getTimestamp();
            log.debug("Proxy config loaded: tenantId={}, hubUrl={}, instanceId={} (saved at: {})",
                    data.getTenantId(), data.getHubUrl(), data.getInstanceId(),
                    new java.util.Date(timestamp));
            return data;
            
        } catch (IOException e) {
            log.warn("Failed to load proxy config: {} (returning empty data)", storagePath, e);
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
                log.info("Proxy config storage file deleted: {}", storagePath);
            } else {
                log.warn("Failed to delete proxy config storage file: {}", storagePath);
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
     * Proxy 설정 데이터 구조
     */
    public static class ConfigData {
        private long timestamp;
        private String tenantId;  // Hub가 발급한 고유 ID
        private String hubUrl;  // Hub URL
        private String instanceId;  // 사용자가 설정한 별칭
        private boolean failOpen;  // Fail-open 모드 여부
        
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
        
        public boolean isFailOpen() {
            return failOpen;
        }
        
        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }
    }
}
