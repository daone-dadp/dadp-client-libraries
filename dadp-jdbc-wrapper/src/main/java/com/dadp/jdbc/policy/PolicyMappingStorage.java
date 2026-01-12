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
 * 정책 매핑 영구 저장소
 * 
 * Hub에서 받은 정책 매핑 정보(테이블.컬럼 → 정책명)를 파일에 저장하고,
 * Hub가 다운되어도 저장된 정보를 사용할 수 있도록 합니다.
 * 
 * @deprecated 이 클래스는 더 이상 사용되지 않습니다.
 *             대신 {@link com.dadp.common.sync.policy.PolicyMappingStorage}를 사용하세요.
 * 
 * @author DADP Development Team
 * @version 4.8.0
 * @since 2025-12-05
 */
@Deprecated
public class PolicyMappingStorage {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(PolicyMappingStorage.class);
    
    private static final String DEFAULT_STORAGE_DIR = System.getProperty("user.dir") + "/.dadp-wrapper";
    private static final String DEFAULT_STORAGE_FILE = "policy-mappings.json";
    
    private final String storagePath;
    private final ObjectMapper objectMapper;
    
    /**
     * 기본 생성자 (사용자 홈 디렉토리 사용)
     */
    public PolicyMappingStorage() {
        this(DEFAULT_STORAGE_DIR, DEFAULT_STORAGE_FILE);
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
                Files.createDirectories(Paths.get(DEFAULT_STORAGE_DIR));
                finalStoragePath = Paths.get(DEFAULT_STORAGE_DIR, fileName).toString();
            } catch (IOException e2) {
                log.error("❌ 기본 저장 디렉토리 생성 실패: {}", DEFAULT_STORAGE_DIR, e2);
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
     * @param mappings 정책 매핑 맵 (테이블.컬럼 → 정책명)
     * @return 저장 성공 여부
     */
    public boolean saveMappings(Map<String, String> mappings) {
        if (storagePath == null) {
            log.warn("⚠️ 저장 경로가 설정되지 않아 정책 매핑 저장 불가");
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
            
            log.info("💾 정책 매핑 정보 저장 완료: {}개 매핑 → {}", mappings.size(), storagePath);
            return true;
            
        } catch (IOException e) {
            log.error("❌ 정책 매핑 정보 저장 실패: {}", storagePath, e);
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
            
            Map<String, String> mappings = data.getMappings();
            long timestamp = data.getTimestamp();
            
            log.info("📂 정책 매핑 정보 로드 완료: {}개 매핑 (저장 시각: {})", 
                    mappings.size(), new java.util.Date(timestamp));
            return mappings;
            
        } catch (IOException e) {
            log.warn("⚠️ 정책 매핑 정보 로드 실패: {} (빈 맵 반환)", storagePath, e);
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

