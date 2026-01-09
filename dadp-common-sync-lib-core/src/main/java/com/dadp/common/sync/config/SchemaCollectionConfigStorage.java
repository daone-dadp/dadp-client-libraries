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
 * 스키마 수집 설정 영구 저장소
 * 
 * Hub에서 받은 스키마 수집 설정 정보를 파일에 저장하고,
 * Hub가 다운되어도 저장된 정보를 사용할 수 있도록 합니다.
 * 
 * @author DADP Development Team
 * @version 5.4.0
 * @since 2026-01-09
 */
public class SchemaCollectionConfigStorage {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(SchemaCollectionConfigStorage.class);
    
    private static final String DEFAULT_STORAGE_FILE = "schema-collection-config.json";
    
    private final String storagePath;
    private final ObjectMapper objectMapper;
    
    /**
     * 기본 저장 디렉토리 조회
     * 시스템 프로퍼티 또는 환경 변수에서 읽고, 없으면 기본값 사용
     * 
     * @return 저장 디렉토리 경로
     */
    private static String getDefaultStorageDir() {
        // 1. 시스템 프로퍼티 확인 (dadp.storage.dir)
        String storageDir = System.getProperty("dadp.storage.dir");
        if (storageDir != null && !storageDir.trim().isEmpty()) {
            return storageDir;
        }
        
        // 2. 환경 변수 확인 (DADP_STORAGE_DIR)
        storageDir = System.getenv("DADP_STORAGE_DIR");
        if (storageDir != null && !storageDir.trim().isEmpty()) {
            return storageDir;
        }
        
        // 3. 기본값 사용 (~/.dadp-wrapper)
        return System.getProperty("user.home") + "/.dadp-wrapper";
    }
    
    /**
     * 기본 생성자 (사용자 홈 디렉토리 사용)
     * 기본 경로는 시스템 프로퍼티(dadp.storage.dir) 또는 환경 변수(DADP_STORAGE_DIR)로 설정 가능
     */
    public SchemaCollectionConfigStorage() {
        this(getDefaultStorageDir(), DEFAULT_STORAGE_FILE);
    }
    
    /**
     * 커스텀 저장 경로 지정
     * 
     * @param storageDir 저장 디렉토리
     * @param fileName 파일명
     */
    public SchemaCollectionConfigStorage(String storageDir, String fileName) {
        // 디렉토리 생성
        Path dirPath = Paths.get(storageDir);
        String finalStoragePath = null;
        try {
            Files.createDirectories(dirPath);
            finalStoragePath = Paths.get(storageDir, fileName).toString();
        } catch (IOException e) {
            log.warn("⚠️ 저장 디렉토리 생성 실패: {} (기본 경로 사용)", storageDir, e);
            // 기본 경로로 폴백
            try {
                String fallbackDir = getDefaultStorageDir();
                Files.createDirectories(Paths.get(fallbackDir));
                finalStoragePath = Paths.get(fallbackDir, fileName).toString();
            } catch (IOException e2) {
                log.error("❌ 기본 저장 디렉토리 생성 실패: {}", getDefaultStorageDir(), e2);
                finalStoragePath = null; // 저장 불가
            }
        }
        
        this.storagePath = finalStoragePath;
        
        this.objectMapper = new ObjectMapper();
        log.info("✅ 스키마 수집 설정 저장소 초기화: {}", this.storagePath);
    }
    
    /**
     * 스키마 수집 설정 저장
     * 
     * @param config 설정 정보 (null 가능)
     * @param version 설정 버전 (null 가능)
     * @return 저장 성공 여부
     */
    public boolean saveConfig(SchemaCollectionConfig config, Long version) {
        if (storagePath == null) {
            log.warn("⚠️ 저장 경로가 설정되지 않아 스키마 수집 설정 저장 불가");
            return false;
        }
        
        try {
            // 저장 데이터 구조
            SchemaCollectionConfigData data = new SchemaCollectionConfigData();
            data.setTimestamp(System.currentTimeMillis());
            data.setConfig(config);
            data.setVersion(version);
            
            // 파일에 저장
            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);
            
            log.info("💾 스키마 수집 설정 저장 완료: timeout={}ms, maxSchemas={}, allowlist={}, failMode={}, version={} → {}", 
                    config != null ? config.getTimeoutMs() : null,
                    config != null ? config.getMaxSchemas() : null,
                    config != null ? config.getAllowlist() : null,
                    config != null ? config.getFailMode() : null,
                    version, storagePath);
            return true;
            
        } catch (IOException e) {
            log.error("❌ 스키마 수집 설정 저장 실패: {}", storagePath, e);
            return false;
        }
    }
    
    /**
     * 스키마 수집 설정 로드
     * 
     * @return 설정 정보, 로드 실패 시 null
     */
    public SchemaCollectionConfig loadConfig() {
        if (storagePath == null) {
            log.warn("⚠️ 저장 경로가 설정되지 않아 스키마 수집 설정 로드 불가");
            return null;
        }
        
        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            log.debug("📋 스키마 수집 설정 저장 파일이 없음: {} (Hub에서 로드 예정)", storagePath);
            return null;
        }
        
        try {
            SchemaCollectionConfigData data = objectMapper.readValue(storageFile, SchemaCollectionConfigData.class);
            
            if (data == null || data.getConfig() == null) {
                log.warn("⚠️ 스키마 수집 설정 데이터가 비어있음: {}", storagePath);
                return null;
            }
            
            SchemaCollectionConfig config = data.getConfig();
            long timestamp = data.getTimestamp();
            Long version = data.getVersion();
            
            log.info("📂 스키마 수집 설정 로드 완료: timeout={}ms, maxSchemas={}, allowlist={}, failMode={}, version={} (저장 시각: {})", 
                    config.getTimeoutMs(), config.getMaxSchemas(), config.getAllowlist(), config.getFailMode(),
                    version, new java.util.Date(timestamp));
            return config;
            
        } catch (IOException e) {
            log.warn("⚠️ 스키마 수집 설정 로드 실패: {} (null 반환)", storagePath, e);
            return null;
        }
    }
    
    /**
     * 저장된 버전 정보 로드
     * 
     * @return 버전 정보 (없으면 null)
     */
    public Long loadVersion() {
        if (storagePath == null) {
            return null;
        }
        
        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            return null;
        }
        
        try {
            SchemaCollectionConfigData data = objectMapper.readValue(storageFile, SchemaCollectionConfigData.class);
            return data != null ? data.getVersion() : null;
        } catch (IOException e) {
            log.warn("⚠️ 버전 정보 로드 실패: {}", storagePath, e);
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
                log.info("🗑️ 스키마 수집 설정 저장 파일 삭제 완료: {}", storagePath);
            } else {
                log.warn("⚠️ 스키마 수집 설정 저장 파일 삭제 실패: {}", storagePath);
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
     * 스키마 수집 설정 데이터 구조
     */
    public static class SchemaCollectionConfigData {
        private long timestamp;
        private SchemaCollectionConfig config;
        private Long version;
        
        public long getTimestamp() {
            return timestamp;
        }
        
        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
        
        public SchemaCollectionConfig getConfig() {
            return config;
        }
        
        public void setConfig(SchemaCollectionConfig config) {
            this.config = config;
        }
        
        public Long getVersion() {
            return version;
        }
        
        public void setVersion(Long version) {
            this.version = version;
        }
    }
    
    /**
     * 스키마 수집 설정 정보
     */
    public static class SchemaCollectionConfig {
        private Long timeoutMs;  // 타임아웃 (밀리초)
        private Integer maxSchemas;  // 최대 스키마 개수
        private String allowlist;  // 허용 스키마 목록 (쉼표로 구분)
        private String failMode;  // 실패 모드 ("fail-open" 또는 "fail-close")
        
        public Long getTimeoutMs() {
            return timeoutMs;
        }
        
        public void setTimeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
        
        public Integer getMaxSchemas() {
            return maxSchemas;
        }
        
        public void setMaxSchemas(Integer maxSchemas) {
            this.maxSchemas = maxSchemas;
        }
        
        public String getAllowlist() {
            return allowlist;
        }
        
        public void setAllowlist(String allowlist) {
            this.allowlist = allowlist;
        }
        
        public String getFailMode() {
            return failMode;
        }
        
        public void setFailMode(String failMode) {
            this.failMode = failMode;
        }
    }
}
