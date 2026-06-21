package com.dadp.jdbc.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Legacy policy mapping storage.
 *
 * @deprecated Use {@link com.dadp.common.sync.policy.PolicyMappingStorage}.
 * 
 * @author DADP Development Team
 * @version 4.8.0
 * @since 2025-12-05
 */
@Deprecated
public class PolicyMappingStorage {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(PolicyMappingStorage.class);
    
    private static final String DEFAULT_STORAGE_FILE = "policy-mappings.json";
    
    private final String storagePath;
    private final ObjectMapper objectMapper;
    
    public PolicyMappingStorage() {
        throw new IllegalStateException("PolicyMappingStorage requires an explicit alias storage directory");
    }
    
    /**
     * Creates storage with an explicit wrapper-managed directory.
     *
     * @param storageDir wrapper-managed storage directory
     * @param fileName storage file name
     */
    public PolicyMappingStorage(String storageDir, String fileName) {
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
        log.debug("Policy mapping storage initialized: {}", this.storagePath);
    }
    
    /**
     * 정책 매핑 정보 저장
     * 
     * @param mappings 정책 매핑 맵 (테이블.컬럼 → 정책명)
     * @return 저장 성공 여부
     */
    public boolean saveMappings(Map<String, String> mappings) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot save policy mappings");
            return false;
        }
        
        try {
            // 저장 데이터 구조
            PolicyMappingData data = new PolicyMappingData();
            data.setTimestamp(System.currentTimeMillis());
            data.setMappings(mappings);
            
            // 파일에 저장
            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);
            
            log.debug("Policy mappings saved: {} mappings -> {}", mappings.size(), storagePath);
            return true;
            
        } catch (IOException e) {
            log.error("Failed to save policy mappings: {}", storagePath, e);
            return false;
        }
    }
    
    /**
     * 정책 매핑 정보 로드
     * 
     * @return 정책 매핑 맵 (테이블.컬럼 → 정책명), 로드 실패 시 빈 맵
     */
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
            
            Map<String, String> mappings = data.getMappings();
            long timestamp = data.getTimestamp();
            
            log.debug("Policy mappings loaded: {} mappings (saved at: {})",
                    mappings.size(), new java.util.Date(timestamp));
            return mappings;
            
        } catch (IOException e) {
            log.warn("Failed to load policy mappings: {} (returning empty map)", storagePath, e);
            return new HashMap<>();
        }
    }
    
    /**
     * 저장 파일 존재 여부 확인
     * 
     * @return 파일 존재 여부
     */
    public boolean hasStoredMappings() {
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
                log.info("Policy mapping storage file deleted: {}", storagePath);
            } else {
                log.warn("Failed to delete policy mapping storage file: {}", storagePath);
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
     * 정책 매핑 데이터 구조
     */
    public static class PolicyMappingData {
        private long timestamp;
        private Map<String, String> mappings;
        
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
    }
}
