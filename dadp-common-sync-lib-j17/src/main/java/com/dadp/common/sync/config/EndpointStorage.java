package com.dadp.common.sync.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 암복호화 엔드포인트 영구 저장소
 * 
 * Hub에서 받은 Engine/Gateway URL 정보를 파일에 저장하고,
 * Hub가 다운되어도 저장된 정보를 사용하여 직접 암복호화 요청을 수행합니다.
 * 
 * @author DADP Development Team
 * @version 5.0.4
 * @since 2025-12-30
 */
public class EndpointStorage {
    
    private static final Logger log = LoggerFactory.getLogger(EndpointStorage.class);
    
    private final String storagePath;
    private final ObjectMapper objectMapper;
    
    /**
     * 커스텀 저장 경로 지정
     * 
     * @param storageDir 저장 디렉토리
     * @param fileName 파일명
     */
    public EndpointStorage(String storageDir, String fileName) {
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
                String defaultDir = System.getProperty("user.home") + "/.dadp";
                Files.createDirectories(Paths.get(defaultDir));
                finalStoragePath = Paths.get(defaultDir, fileName).toString();
            } catch (IOException e2) {
                log.error("❌ 기본 저장 디렉토리 생성 실패", e2);
                finalStoragePath = null; // 저장 불가
            }
        }
        
        this.storagePath = finalStoragePath;
        
        this.objectMapper = new ObjectMapper();
        log.info("✅ 암복호화 엔드포인트 저장소 초기화: {}", this.storagePath);
    }
    
    /**
     * 엔드포인트 정보 저장
     * 
     * @param cryptoUrl 암복호화에 사용할 단일 URL
     * @param hubId Hub가 발급한 인스턴스 고유 ID
     * @param version Hub의 최신 버전 (hubVersion)
     * @param statsAggregatorEnabled 통계 앱 사용 여부
     * @param statsAggregatorUrl 통계 앱 URL
     * @param statsAggregatorMode 전송 모드 (DIRECT/GATEWAY)
     * @param slowThresholdMs Slow SQL threshold (ms)
     * @return 저장 성공 여부
     */
    public boolean saveEndpoints(String cryptoUrl, String hubId, Long version,
                                  Boolean statsAggregatorEnabled, String statsAggregatorUrl, String statsAggregatorMode,
                                  Integer slowThresholdMs) {
        if (storagePath == null) {
            log.warn("⚠️ 저장 경로가 설정되지 않아 엔드포인트 정보 저장 불가");
            return false;
        }

        try {
            // 저장 데이터 구조
            EndpointData data = new EndpointData();
            data.setCryptoUrl(cryptoUrl);
            data.setHubId(hubId);
            data.setVersion(version);
            data.setStatsAggregatorEnabled(statsAggregatorEnabled);
            data.setStatsAggregatorUrl(statsAggregatorUrl);
            data.setStatsAggregatorMode(statsAggregatorMode);
            data.setSlowThresholdMs(slowThresholdMs);
            
            // 파일에 저장
            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);
            
            log.info("💾 엔드포인트 및 통계 설정 정보 저장 완료: cryptoUrl={}, hubId={}, version={} → {}", 
                    cryptoUrl, hubId, version, storagePath);
            return true;
            
        } catch (IOException e) {
            log.error("❌ 엔드포인트 정보 저장 실패: {}", storagePath, e);
            return false;
        }
    }
    
    /**
     * 엔드포인트 정보 로드
     * 
     * @return 엔드포인트 데이터, 로드 실패 시 null
     */
    public EndpointData loadEndpoints() {
        if (storagePath == null) {
            log.warn("⚠️ 저장 경로가 설정되지 않아 엔드포인트 정보 로드 불가");
            return null;
        }
        
        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            log.debug("📋 암복호화 엔드포인트 저장 파일이 없음: {} (Hub에서 조회 예정)", storagePath);
            return null;
        }
        
        try {
            EndpointData data = objectMapper.readValue(storageFile, EndpointData.class);
            
            if (data == null) {
                log.warn("⚠️ 암복호화 엔드포인트 데이터가 비어있음: {}", storagePath);
                return null;
            }
            
            log.debug("📂 암복호화 엔드포인트 정보 로드 완료: cryptoUrl={}, hubId={}, version={}", 
                    data.getCryptoUrl(), data.getHubId(), data.getVersion());
            return data;
            
        } catch (IOException e) {
            log.warn("⚠️ 암복호화 엔드포인트 정보 로드 실패: {} (빈 데이터 반환)", storagePath, e);
            return null;
        }
    }
    
    /**
     * 저장 파일 존재 여부 확인
     * 
     * @return 파일 존재 여부
     */
    public boolean hasStoredEndpoints() {
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
                log.info("🗑️ 암복호화 엔드포인트 저장 파일 삭제 완료: {}", storagePath);
            } else {
                log.warn("⚠️ 암복호화 엔드포인트 저장 파일 삭제 실패: {}", storagePath);
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
     * 엔드포인트 데이터 구조
     */
    public static class EndpointData {
        // 필수 필드
        private String cryptoUrl;  // 암복호화에 사용할 단일 URL
        private String hubId;      // Hub가 발급한 인스턴스 고유 ID
        private Long version;      // Hub의 최신 버전 (hubVersion)
        
        // 통계 설정
        private Boolean statsAggregatorEnabled;  // 통계 앱 사용 여부
        private String statsAggregatorUrl;       // 통계 앱 URL
        private String statsAggregatorMode;      // 전송 모드 (DIRECT/GATEWAY)
        private Integer slowThresholdMs;          // Slow SQL threshold (ms)
        
        // Getters and Setters
        public String getCryptoUrl() {
            return cryptoUrl;
        }
        
        public void setCryptoUrl(String cryptoUrl) {
            this.cryptoUrl = cryptoUrl;
        }
        
        public String getHubId() {
            return hubId;
        }
        
        public void setHubId(String hubId) {
            this.hubId = hubId;
        }
        
        public Long getVersion() {
            return version;
        }
        
        public void setVersion(Long version) {
            this.version = version;
        }
        
        public Boolean getStatsAggregatorEnabled() {
            return statsAggregatorEnabled;
        }
        
        public void setStatsAggregatorEnabled(Boolean statsAggregatorEnabled) {
            this.statsAggregatorEnabled = statsAggregatorEnabled;
        }
        
        public String getStatsAggregatorUrl() {
            return statsAggregatorUrl;
        }
        
        public void setStatsAggregatorUrl(String statsAggregatorUrl) {
            this.statsAggregatorUrl = statsAggregatorUrl;
        }
        
        public String getStatsAggregatorMode() {
            return statsAggregatorMode;
        }
        
        public void setStatsAggregatorMode(String statsAggregatorMode) {
            this.statsAggregatorMode = statsAggregatorMode;
        }
        
        public Integer getSlowThresholdMs() {
            return slowThresholdMs;
        }
        
        public void setSlowThresholdMs(Integer slowThresholdMs) {
            this.slowThresholdMs = slowThresholdMs;
        }
    }
}

