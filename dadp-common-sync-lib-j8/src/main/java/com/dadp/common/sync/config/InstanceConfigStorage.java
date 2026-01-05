package com.dadp.common.sync.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Hub에서 받은 hubId 및 설정 정보를 파일에 저장하고,
 * 재시작 시에도 저장된 정보를 사용합니다.
 * 
 * WRAPPER와 AOP 모두 사용 가능하도록 설계되었습니다.
 * - WRAPPER: ~/.dadp-wrapper/proxy-config.json
 * - AOP: ~/.dadp-aop/aop-config.json
 * 
 * @author DADP Development Team
 * @version 5.0.4
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
            log.warn("⚠️ 저장 디렉토리 생성 실패: {} (저장 불가)", storageDir, e);
            finalStoragePath = null; // 저장 불가
        }
        
        this.storagePath = finalStoragePath;
        this.objectMapper = new ObjectMapper();
        if (finalStoragePath != null) {
            log.info("✅ 인스턴스 설정 저장소 초기화: {}", this.storagePath);
        } else {
            log.warn("⚠️ 인스턴스 설정 저장소 초기화 실패: 저장 불가");
        }
    }
    
    /**
     * 인스턴스 설정 저장
     * 
     * @param hubId Hub가 발급한 고유 ID
     * @param hubUrl Hub URL
     * @param instanceId 사용자가 설정한 별칭
     * @param failOpen Fail-open 모드 여부 (WRAPPER용, AOP는 무시 가능)
     * @return 저장 성공 여부
     */
    public boolean saveConfig(String hubId, String hubUrl, String instanceId, Boolean failOpen) {
        if (storagePath == null) {
            log.warn("⚠️ 저장 경로가 설정되지 않아 인스턴스 설정 저장 불가");
            return false;
        }
        
        try {
            // 저장 데이터 구조
            ConfigData data = new ConfigData();
            data.setTimestamp(System.currentTimeMillis());
            data.setHubId(hubId);
            data.setHubUrl(hubUrl);
            data.setInstanceId(instanceId);
            if (failOpen != null) {
                data.setFailOpen(failOpen);
            }
            
            // 파일에 저장
            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);
            
            log.info("💾 인스턴스 설정 저장 완료: hubId={}, hubUrl={}, instanceId={} → {}", 
                    hubId, hubUrl, instanceId, storagePath);
            return true;
            
        } catch (IOException e) {
            log.error("❌ 인스턴스 설정 저장 실패: {}", storagePath, e);
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
            log.warn("⚠️ 저장 경로가 설정되지 않아 인스턴스 설정 로드 불가");
            return null;
        }
        
        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            log.debug("📋 인스턴스 설정 저장 파일이 없음: {} (Hub에서 등록 예정)", storagePath);
            return null;
        }
        
        try {
            ConfigData data = objectMapper.readValue(storageFile, ConfigData.class);
            
            if (data == null) {
                log.warn("⚠️ 인스턴스 설정 데이터가 비어있음: {}", storagePath);
                return null;
            }
            
            // hubUrl과 instanceId가 일치하는 경우만 로드
            if (hubUrl != null && !hubUrl.equals(data.getHubUrl())) {
                log.debug("📋 Hub URL이 일치하지 않아 설정 로드 건너뜀: 저장된={}, 요청={}", 
                        data.getHubUrl(), hubUrl);
                return null;
            }
            
            if (instanceId != null && !instanceId.equals(data.getInstanceId())) {
                log.debug("📋 인스턴스 ID가 일치하지 않아 설정 로드 건너뜀: 저장된={}, 요청={}", 
                        data.getInstanceId(), instanceId);
                return null;
            }
            
            long timestamp = data.getTimestamp();
            log.info("📂 인스턴스 설정 로드 완료: hubId={}, hubUrl={}, instanceId={} (저장 시각: {})", 
                    data.getHubId(), data.getHubUrl(), data.getInstanceId(),
                    new java.util.Date(timestamp));
            return data;
            
        } catch (IOException e) {
            log.warn("⚠️ 인스턴스 설정 로드 실패: {} (빈 데이터 반환)", storagePath, e);
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
                log.info("🗑️ 인스턴스 설정 저장 파일 삭제 완료: {}", storagePath);
            } else {
                log.warn("⚠️ 인스턴스 설정 저장 파일 삭제 실패: {}", storagePath);
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
    public static class ConfigData {
        private long timestamp;
        private String hubId;  // Hub가 발급한 고유 ID
        private String hubUrl;  // Hub URL
        private String instanceId;  // 사용자가 설정한 별칭
        private Boolean failOpen;  // Fail-open 모드 여부 (WRAPPER용, AOP는 null 가능)
        
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
        
        public Boolean getFailOpen() {
            return failOpen;
        }
        
        public void setFailOpen(Boolean failOpen) {
            this.failOpen = failOpen;
        }
    }
}

