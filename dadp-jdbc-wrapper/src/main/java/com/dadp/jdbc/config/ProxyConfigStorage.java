package com.dadp.jdbc.config;

import com.dadp.common.sync.config.StoragePathResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Proxy 설정 영구 저장소
 * 
 * @deprecated 이 클래스는 더 이상 사용되지 않습니다.
 *             공통 라이브러리의 {@link com.dadp.common.sync.config.InstanceConfigStorage}를 사용하세요.
 *             
 *             마이그레이션:
 *             - 기존: new ProxyConfigStorage()
 *             - 신규: new InstanceConfigStorage(storageDir, fileName)
 *             
 *             이 클래스는 하위 호환성을 위해 유지되지만, 새로운 코드에서는 사용하지 마세요.
 * 
 * @author DADP Development Team
 * @version 4.8.1
 * @since 2025-12-16
 * @see com.dadp.common.sync.config.InstanceConfigStorage
 */
@Deprecated
public class ProxyConfigStorage {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(ProxyConfigStorage.class);
    
    private static final String DEFAULT_STORAGE_DIR = StoragePathResolver.resolveStorageDir();
    private static final String DEFAULT_STORAGE_FILE = "proxy-config.json";
    
    private final String storagePath;
    private final ObjectMapper objectMapper;
    
    /**
     * 기본 생성자 (사용자 홈 디렉토리 사용)
     */
    public ProxyConfigStorage() {
        this(DEFAULT_STORAGE_DIR, DEFAULT_STORAGE_FILE);
    }
    
    /**
     * 커스텀 저장 경로 지정
     * 
     * @param storageDir 저장 디렉토리
     * @param fileName 파일명
     */
    public ProxyConfigStorage(String storageDir, String fileName) {
        // 디렉토리 생성
        Path dirPath = Paths.get(storageDir);
        String finalStoragePath = null;
        try {
            Files.createDirectories(dirPath);
            finalStoragePath = Paths.get(storageDir, fileName).toString();
        } catch (IOException e) {
            log.warn("Failed to create storage directory: {} (using default path)", storageDir, e);
            // 기본 경로로 폴백
            try {
                Files.createDirectories(Paths.get(DEFAULT_STORAGE_DIR));
                finalStoragePath = Paths.get(DEFAULT_STORAGE_DIR, fileName).toString();
            } catch (IOException e2) {
                log.error("Failed to create default storage directory: {}", DEFAULT_STORAGE_DIR, e2);
                finalStoragePath = null; // 저장 불가
            }
        }
        
        this.storagePath = finalStoragePath;
        this.objectMapper = new ObjectMapper();
        log.debug("Proxy config storage initialized: {}", this.storagePath);
    }
    
    /**
     * Proxy 설정 저장
     * 
     * @param hubId Hub가 발급한 고유 ID
     * @param hubUrl Hub URL
     * @param instanceId 사용자가 설정한 별칭
     * @param failOpen Fail-open 모드 여부
     * @return 저장 성공 여부
     */
    public boolean saveConfig(String hubId, String hubUrl, String instanceId, boolean failOpen) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot save proxy config");
            return false;
        }
        
        try {
            // 저장 데이터 구조
            ConfigData data = new ConfigData();
            data.setTimestamp(System.currentTimeMillis());
            data.setHubId(hubId);
            data.setHubUrl(hubUrl);
            data.setInstanceId(instanceId);
            data.setFailOpen(failOpen);
            
            // 파일에 저장
            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);
            
            log.debug("Proxy config saved: hubId={}, hubUrl={}, instanceId={} -> {}",
                    hubId, hubUrl, instanceId, storagePath);
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
            log.debug("Proxy config loaded: hubId={}, hubUrl={}, instanceId={} (saved at: {})",
                    data.getHubId(), data.getHubUrl(), data.getInstanceId(),
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
        private String hubId;  // Hub가 발급한 고유 ID
        private String hubUrl;  // Hub URL
        private String instanceId;  // 사용자가 설정한 별칭
        private boolean failOpen;  // Fail-open 모드 여부
        
        public long getTimestamp() {
            return timestamp;
        }
        
        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
        
        public String getHubId() {
            return hubId;
        }
        
        public void setHubId(String hubId) {
            this.hubId = hubId;
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
