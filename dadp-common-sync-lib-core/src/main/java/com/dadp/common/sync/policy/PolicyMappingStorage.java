package com.dadp.common.sync.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 정책 매핑 영구 저장소
 * 
 * Hub에서 받은 정책 매핑 정보(테이블.컬럼 → 정책명)를 파일에 저장하고,
 * Hub가 다운되어도 저장된 정보를 사용할 수 있도록 합니다.
 * 
 * @author DADP Development Team
 * @version 5.0.9
 * @since 2025-12-30
 */
public class PolicyMappingStorage {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(PolicyMappingStorage.class);
    
    private static final String DEFAULT_STORAGE_FILE = "policy-mappings.json";
    
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
    public PolicyMappingStorage() {
        this(getDefaultStorageDir(), DEFAULT_STORAGE_FILE);
    }
    
    /**
     * 커스텀 저장 경로 지정
     * 
     * @param storageDir 저장 디렉토리
     * @param fileName 파일명
     */
    public PolicyMappingStorage(String storageDir, String fileName) {
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
        log.info("✅ 정책 매핑 저장소 초기화: {}", this.storagePath);
    }
    
    /**
     * 정책 매핑 정보 저장
     * 
     * @param mappings 정책 매핑 맵 (테이블.컬럼 → 정책명, null 가능)
     *                 키가 스키마 정보(table.column)이고, 값이 null이면 스키마는 있지만 정책이 없는 상태
     * @param version 정책 버전 (null 가능)
     * @return 저장 성공 여부
     */
    public boolean saveMappings(Map<String, String> mappings, Long version) {
        if (storagePath == null) {
            log.warn("⚠️ 저장 경로가 설정되지 않아 정책 매핑 저장 불가");
            return false;
        }
        
        try {
            // 저장 데이터 구조
            PolicyMappingData data = new PolicyMappingData();
            data.setStorageSchemaVersion(PolicyMappingData.CURRENT_STORAGE_SCHEMA_VERSION);
            data.setTimestamp(System.currentTimeMillis());
            data.setMappings(mappings);
            data.setVersion(version);
            
            // 파일에 저장
            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);
            
            log.info("💾 정책 매핑 정보 저장 완료: {}개 매핑, version={}, storageSchemaVersion={} → {}", 
                    mappings.size(), version, PolicyMappingData.CURRENT_STORAGE_SCHEMA_VERSION, storagePath);
            return true;
            
        } catch (IOException e) {
            log.error("❌ 정책 매핑 정보 저장 실패: {}", storagePath, e);
            return false;
        }
    }
    
    /**
     * 정책 매핑 정보 저장 (버전 없음)
     * 
     * @param mappings 정책 매핑 맵 (테이블.컬럼 → 정책명, null 가능)
     * @return 저장 성공 여부
     */
    public boolean saveMappings(Map<String, String> mappings) {
        return saveMappings(mappings, null);
    }
    
    /**
     * 정책 매핑 정보 로드
     * 
     * @return 정책 매핑 맵 (테이블.컬럼 → 정책명), 로드 실패 시 빈 맵
     */
    public Map<String, String> loadMappings() {
        if (storagePath == null) {
            log.warn("⚠️ 저장 경로가 설정되지 않아 정책 매핑 로드 불가");
            return new HashMap<>();
        }
        
        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            log.debug("📋 정책 매핑 저장 파일이 없음: {} (새로 생성될 예정)", storagePath);
            return new HashMap<>();
        }
        
        try {
            PolicyMappingData data = objectMapper.readValue(storageFile, PolicyMappingData.class);
            
            if (data == null || data.getMappings() == null) {
                log.warn("⚠️ 정책 매핑 데이터가 비어있음: {}", storagePath);
                return new HashMap<>();
            }
            
            // 저장소 포맷 버전 확인 및 하위 호환성 처리
            int storageVersion = data.getStorageSchemaVersion();
            if (storageVersion == 0) {
                // 구버전 포맷 (버전 필드 없음) -> 버전 1로 간주
                log.info("📋 구버전 정책 매핑 포맷 감지 (버전 필드 없음) -> 버전 1으로 처리");
                storageVersion = 1;
            }
            
            // 향후 버전 호환성 체크
            if (storageVersion > PolicyMappingData.CURRENT_STORAGE_SCHEMA_VERSION) {
                log.warn("⚠️ 알 수 없는 정책 매핑 포맷 버전: {} (현재 지원 버전: {}), " +
                        "하위 호환성 보장을 위해 계속 진행합니다", 
                    storageVersion, PolicyMappingData.CURRENT_STORAGE_SCHEMA_VERSION);
            }
            
            Map<String, String> mappings = data.getMappings();
            long timestamp = data.getTimestamp();
            Long version = data.getVersion();
            
            log.info("📂 정책 매핑 정보 로드 완료: {}개 매핑, version={}, storageSchemaVersion={} (저장 시각: {})", 
                    mappings.size(), version, storageVersion, new java.util.Date(timestamp));
            return mappings;
            
        } catch (IOException e) {
            log.warn("⚠️ 정책 매핑 정보 로드 실패: {} (빈 맵 반환)", storagePath, e);
            return new HashMap<>();
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
            PolicyMappingData data = objectMapper.readValue(storageFile, PolicyMappingData.class);
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
                log.info("🗑️ 정책 매핑 저장 파일 삭제 완료: {}", storagePath);
            } else {
                log.warn("⚠️ 정책 매핑 저장 파일 삭제 실패: {}", storagePath);
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
     * mappings의 키가 스키마 정보(table.column)이고, 값이 null이면 스키마는 있지만 정책이 없는 상태
     */
    public static class PolicyMappingData {
        private static final int CURRENT_STORAGE_SCHEMA_VERSION = 1;  // 현재 저장소 포맷 버전
        
        private int storageSchemaVersion = CURRENT_STORAGE_SCHEMA_VERSION;  // 저장소 포맷 버전
        private long timestamp;
        private Map<String, String> mappings; // 테이블.컬럼 → 정책명 (null 가능)
        private Long version;
        
        public int getStorageSchemaVersion() {
            return storageSchemaVersion;
        }
        
        public void setStorageSchemaVersion(int storageSchemaVersion) {
            this.storageSchemaVersion = storageSchemaVersion;
        }
        
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
        
        public Long getVersion() {
            return version;
        }
        
        public void setVersion(Long version) {
            this.version = version;
        }
    }
}

