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
 * 암복호화 엔드포인트 영구 저장소
 * 
 * Hub에서 받은 Engine/Gateway URL 정보를 파일에 저장하고,
 * Hub가 다운되어도 저장된 정보를 사용하여 직접 암복호화 요청을 수행합니다.
 * 
 * @author DADP Development Team
 * @version 5.0.9
 * @since 2025-12-30
 */
public class EndpointStorage {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(EndpointStorage.class);
    
    private static final String DEFAULT_STORAGE_FILE = "crypto-endpoints.json";
    
    // 싱글톤 인스턴스 (기본 경로 사용)
    private static volatile EndpointStorage defaultInstance = null;
    private static final Object singletonLock = new Object();
    
    private final String storagePath;
    private final ObjectMapper objectMapper;
    
    /**
     * 기본 저장 디렉토리 조회
     * 시스템 프로퍼티 또는 환경 변수에서 읽고, 없으면 기본값 사용
     * 
     * @return 저장 디렉토리 경로
     */
    private static String getDefaultStorageDir() {
        return getDefaultStorageDir(null);
    }
    
    /**
     * 기본 저장 디렉토리 조회 (instanceId 사용)
     * 시스템 프로퍼티 또는 환경 변수에서 읽고, 없으면 ./dadp/wrapper/instanceId 형태로 생성
     * 
     * @param instanceId 인스턴스 ID (별칭, 앱 구동 시점에 알 수 있음)
     * @return 저장 디렉토리 경로
     */
    private static String getDefaultStorageDir(String instanceId) {
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
        
        // 3. instanceId를 사용하여 경로 생성
        if (instanceId != null && !instanceId.trim().isEmpty()) {
            // ./dadp/wrapper/instanceId 형태로 생성
            return System.getProperty("user.dir") + "/dadp/wrapper/" + instanceId.trim();
        }
        
        // 4. 기본값 사용 (앱 구동 위치/.dadp-wrapper)
        return System.getProperty("user.dir") + "/.dadp-wrapper";
    }
    
    /**
     * 싱글톤 인스턴스 조회 (기본 경로 사용)
     * 기본 경로는 시스템 프로퍼티(dadp.storage.dir) 또는 환경 변수(DADP_STORAGE_DIR)로 설정 가능
     * 
     * @return 싱글톤 EndpointStorage 인스턴스
     */
    public static EndpointStorage getInstance() {
        if (defaultInstance == null) {
            synchronized (singletonLock) {
                if (defaultInstance == null) {
                    defaultInstance = new EndpointStorage();
                }
            }
        }
        return defaultInstance;
    }
    
    /**
     * 기본 생성자 (사용자 홈 디렉토리 사용)
     * 기본 경로는 시스템 프로퍼티(dadp.storage.dir) 또는 환경 변수(DADP_STORAGE_DIR)로 설정 가능
     */
    public EndpointStorage() {
        this(getDefaultStorageDir(), DEFAULT_STORAGE_FILE);
    }
    
    /**
     * instanceId를 사용한 생성자
     * 
     * @param instanceId 인스턴스 ID (별칭, 앱 구동 시점에 알 수 있음)
     */
    public EndpointStorage(String instanceId) {
        this(getDefaultStorageDir(instanceId), DEFAULT_STORAGE_FILE);
    }
    
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
        log.debug("✅ 암복호화 엔드포인트 저장소 초기화: {}", this.storagePath);
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
            data.setStorageSchemaVersion(EndpointData.CURRENT_STORAGE_SCHEMA_VERSION);
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
            
            log.info("💾 엔드포인트 및 통계 설정 정보 저장 완료: cryptoUrl={}, hubId={}, version={}, storageSchemaVersion={} → {}", 
                    cryptoUrl, hubId, version, EndpointData.CURRENT_STORAGE_SCHEMA_VERSION, storagePath);
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
            
            // 저장소 포맷 버전 확인 및 하위 호환성 처리
            int storageVersion = data.getStorageSchemaVersion();
            if (storageVersion == 0) {
                // 구버전 포맷 (버전 필드 없음) -> 버전 1로 간주
                log.info("📋 구버전 엔드포인트 포맷 감지 (버전 필드 없음) -> 버전 1로 처리");
                storageVersion = 1;
            }
            
            // 향후 버전 호환성 체크
            if (storageVersion > EndpointData.CURRENT_STORAGE_SCHEMA_VERSION) {
                log.warn("⚠️ 알 수 없는 엔드포인트 포맷 버전: {} (현재 지원 버전: {}), " +
                        "하위 호환성 보장을 위해 계속 진행합니다", 
                    storageVersion, EndpointData.CURRENT_STORAGE_SCHEMA_VERSION);
            }
            
            log.debug("📂 암복호화 엔드포인트 정보 로드 완료: cryptoUrl={}, hubId={}, version={}, storageSchemaVersion={}", 
                    data.getCryptoUrl(), data.getHubId(), data.getVersion(), storageVersion);
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
     * 
     * 저장 필수 데이터:
     * - storageSchemaVersion: 저장소 포맷 버전
     * - cryptoUrl: 암복호화에 사용할 단일 URL
     * - hubId: Hub가 발급한 인스턴스 고유 ID
     * - version: Hub의 최신 버전 (hubVersion)
     * - statsAggregatorEnabled: 통계 앱 사용 여부
     * - statsAggregatorUrl: 통계 앱 URL
     * - statsAggregatorMode: 전송 모드 (DIRECT/GATEWAY)
     */
    public static class EndpointData {
        private static final int CURRENT_STORAGE_SCHEMA_VERSION = 1;  // 현재 저장소 포맷 버전
        
        private int storageSchemaVersion = CURRENT_STORAGE_SCHEMA_VERSION;  // 저장소 포맷 버전
        // 필수 필드
        private String cryptoUrl;  // 암복호화에 사용할 단일 URL
        private String hubId;      // Hub가 발급한 인스턴스 고유 ID
        private Long version;      // Hub의 최신 버전 (hubVersion)
        
        // 통계 설정
        private Boolean statsAggregatorEnabled;  // 통계 앱 사용 여부
        private String statsAggregatorUrl;       // 통계 앱 URL
        private String statsAggregatorMode;      // 전송 모드 (DIRECT/GATEWAY)
        // 통계 세부 옵션
        private Integer bufferMaxEvents;
        private Integer flushMaxEvents;
        private Integer flushIntervalMillis;
        private Integer maxBatchSize;
        private Integer maxPayloadBytes;
        private Double samplingRate;
        private Boolean includeSqlNormalized;
        private Boolean includeParams;
        private Boolean normalizeSqlEnabled;
        private Integer httpConnectTimeoutMillis;
        private Integer httpReadTimeoutMillis;
        private Integer retryOnFailure;
        private Integer slowThresholdMs;  // Slow SQL threshold (ms)
        
        // 저장소 포맷 버전 Getters and Setters
        public int getStorageSchemaVersion() {
            return storageSchemaVersion;
        }
        
        public void setStorageSchemaVersion(int storageSchemaVersion) {
            this.storageSchemaVersion = storageSchemaVersion;
        }
        
        // 필수 필드 Getters and Setters
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
        
        // 통계 설정 Getters and Setters
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

        public Integer getBufferMaxEvents() {
            return bufferMaxEvents;
        }

        public void setBufferMaxEvents(Integer bufferMaxEvents) {
            this.bufferMaxEvents = bufferMaxEvents;
        }

        public Integer getFlushMaxEvents() {
            return flushMaxEvents;
        }

        public void setFlushMaxEvents(Integer flushMaxEvents) {
            this.flushMaxEvents = flushMaxEvents;
        }

        public Integer getFlushIntervalMillis() {
            return flushIntervalMillis;
        }

        public void setFlushIntervalMillis(Integer flushIntervalMillis) {
            this.flushIntervalMillis = flushIntervalMillis;
        }

        public Integer getMaxBatchSize() {
            return maxBatchSize;
        }

        public void setMaxBatchSize(Integer maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
        }

        public Integer getMaxPayloadBytes() {
            return maxPayloadBytes;
        }

        public void setMaxPayloadBytes(Integer maxPayloadBytes) {
            this.maxPayloadBytes = maxPayloadBytes;
        }

        public Double getSamplingRate() {
            return samplingRate;
        }

        public void setSamplingRate(Double samplingRate) {
            this.samplingRate = samplingRate;
        }

        public Boolean getIncludeSqlNormalized() {
            return includeSqlNormalized;
        }

        public void setIncludeSqlNormalized(Boolean includeSqlNormalized) {
            this.includeSqlNormalized = includeSqlNormalized;
        }

        public Boolean getIncludeParams() {
            return includeParams;
        }

        public void setIncludeParams(Boolean includeParams) {
            this.includeParams = includeParams;
        }

        public Boolean getNormalizeSqlEnabled() {
            return normalizeSqlEnabled;
        }

        public void setNormalizeSqlEnabled(Boolean normalizeSqlEnabled) {
            this.normalizeSqlEnabled = normalizeSqlEnabled;
        }

        public Integer getHttpConnectTimeoutMillis() {
            return httpConnectTimeoutMillis;
        }

        public void setHttpConnectTimeoutMillis(Integer httpConnectTimeoutMillis) {
            this.httpConnectTimeoutMillis = httpConnectTimeoutMillis;
        }

        public Integer getHttpReadTimeoutMillis() {
            return httpReadTimeoutMillis;
        }

        public void setHttpReadTimeoutMillis(Integer httpReadTimeoutMillis) {
            this.httpReadTimeoutMillis = httpReadTimeoutMillis;
        }

        public Integer getRetryOnFailure() {
            return retryOnFailure;
        }

        public void setRetryOnFailure(Integer retryOnFailure) {
            this.retryOnFailure = retryOnFailure;
        }

        public Integer getSlowThresholdMs() {
            return slowThresholdMs;
        }

        public void setSlowThresholdMs(Integer slowThresholdMs) {
            this.slowThresholdMs = slowThresholdMs;
        }
    }
}

