package com.dadp.common.sync.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.common.sync.config.StoragePathResolver;

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
        return StoragePathResolver.resolveStorageDir();
    }
    
    /**
     * 기본 저장 디렉토리 조회 (instanceId 사용)
     * 시스템 프로퍼티 또는 환경 변수에서 읽고, 없으면 ./dadp/wrapper/instanceId 형태로 생성
     * 
     * @param instanceId 인스턴스 ID (별칭, 앱 구동 시점에 알 수 있음)
     * @return 저장 디렉토리 경로
     */
    private static String getDefaultStorageDir(String instanceId) {
        return StoragePathResolver.resolveStorageDir(instanceId);
    }
    
    /**
     * 기본 생성자 (사용자 홈 디렉토리 사용)
     * 기본 경로는 시스템 프로퍼티(dadp.storage.dir) 또는 환경 변수(DADP_STORAGE_DIR)로 설정 가능
     */
    public PolicyMappingStorage() {
        this(getDefaultStorageDir(), DEFAULT_STORAGE_FILE);
    }
    
    /**
     * instanceId를 사용한 생성자
     * 
     * @param instanceId 인스턴스 ID (별칭, 앱 구동 시점에 알 수 있음)
     */
    public PolicyMappingStorage(String instanceId) {
        this(getDefaultStorageDir(instanceId), DEFAULT_STORAGE_FILE);
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
            log.warn("Failed to create storage directory: {} (using default path)", storageDir, e);
            // 기본 경로로 폴백
            try {
                String fallbackDir = getDefaultStorageDir();
                Files.createDirectories(Paths.get(fallbackDir));
                finalStoragePath = Paths.get(fallbackDir, fileName).toString();
            } catch (IOException e2) {
                log.warn("Failed to create default storage directory: {}", getDefaultStorageDir(), e2);
                finalStoragePath = null; // 저장 불가
            }
        }

        this.storagePath = finalStoragePath;

        this.objectMapper = new ObjectMapper();
        log.info("Policy mapping storage initialized: {}", this.storagePath);
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
            log.warn("Storage path not set, cannot save policy mappings");
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

            log.debug("Policy mapping saved: {} mappings, version={}, storageSchemaVersion={} -> {}",
                    mappings.size(), version, PolicyMappingData.CURRENT_STORAGE_SCHEMA_VERSION, storagePath);
            return true;

        } catch (IOException e) {
            log.warn("Policy mapping save failed: {}", storagePath, e);
            return false;
        }
    }

    /**
     * 정책 매핑 + 정책 속성 저장
     *
     * @param mappings 정책 매핑 맵
     * @param policyAttributes 정책 속성 맵 (policyName → attributes)
     * @param version 정책 버전
     * @return 저장 성공 여부
     */
    public boolean saveMappings(Map<String, String> mappings, Map<String, PolicyResolver.PolicyAttributes> policyAttributes, Long version) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot save policy mappings");
            return false;
        }

        try {
            PolicyMappingData data = new PolicyMappingData();
            data.setStorageSchemaVersion(PolicyMappingData.CURRENT_STORAGE_SCHEMA_VERSION);
            data.setTimestamp(System.currentTimeMillis());
            data.setMappings(mappings);
            data.setVersion(version);

            // 정책 속성 변환 (PolicyResolver.PolicyAttributes → PolicyAttributesData)
            if (policyAttributes != null && !policyAttributes.isEmpty()) {
                Map<String, PolicyAttributesData> attrDataMap = new HashMap<>();
                for (Map.Entry<String, PolicyResolver.PolicyAttributes> entry : policyAttributes.entrySet()) {
                    PolicyAttributesData attrData = new PolicyAttributesData();
                    attrData.setUseIv(entry.getValue().getUseIv());
                    attrData.setUsePlain(entry.getValue().getUsePlain());
                    attrDataMap.put(entry.getKey(), attrData);
                }
                data.setPolicyAttributes(attrDataMap);
            }

            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);

            log.debug("Policy mapping and attributes saved: {} mappings, {} attributes, version={} -> {}",
                    mappings.size(), policyAttributes != null ? policyAttributes.size() : 0, version, storagePath);
            return true;
        } catch (IOException e) {
            log.warn("Policy mapping save failed: {}", storagePath, e);
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
            
            // 저장소 포맷 버전 확인 및 하위 호환성 처리
            int storageVersion = data.getStorageSchemaVersion();
            if (storageVersion == 0) {
                // 구버전 포맷 (버전 필드 없음) -> 버전 1로 간주
                log.debug("Legacy policy mapping format detected (no version field) -> treating as version 1");
                storageVersion = 1;
            }
            
            // 향후 버전 호환성 체크
            if (storageVersion > PolicyMappingData.CURRENT_STORAGE_SCHEMA_VERSION) {
                log.warn("Unknown policy mapping format version: {} (current supported version: {}), " +
                        "proceeding for backward compatibility",
                    storageVersion, PolicyMappingData.CURRENT_STORAGE_SCHEMA_VERSION);
            }
            
            Map<String, String> mappings = data.getMappings();
            long timestamp = data.getTimestamp();
            Long version = data.getVersion();
            
            log.debug("Policy mapping loaded: {} mappings, version={}, storageSchemaVersion={} (saved at: {})",
                    mappings.size(), version, storageVersion, new java.util.Date(timestamp));
            return mappings;
            
        } catch (IOException e) {
            log.warn("Policy mapping load failed: {} (returning empty map)", storagePath, e);
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
            log.warn("Version info load failed: {}", storagePath, e);
            return null;
        }
    }
    
    /**
     * 정책 속성 로드
     *
     * @return 정책 속성 맵 (policyName → PolicyAttributes), 없으면 빈 맵
     */
    public Map<String, PolicyResolver.PolicyAttributes> loadPolicyAttributes() {
        if (storagePath == null) {
            return new HashMap<>();
        }

        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            return new HashMap<>();
        }

        try {
            PolicyMappingData data = objectMapper.readValue(storageFile, PolicyMappingData.class);
            if (data == null || data.getPolicyAttributes() == null) {
                return new HashMap<>();
            }

            // PolicyAttributesData → PolicyResolver.PolicyAttributes 변환
            Map<String, PolicyResolver.PolicyAttributes> result = new HashMap<>();
            for (Map.Entry<String, PolicyAttributesData> entry : data.getPolicyAttributes().entrySet()) {
                PolicyAttributesData attrData = entry.getValue();
                PolicyResolver.PolicyAttributes attrs = new PolicyResolver.PolicyAttributes(
                        attrData.getUseIv(), attrData.getUsePlain());
                result.put(entry.getKey(), attrs);
            }

            log.debug("Policy attributes loaded: {} entries", result.size());
            return result;
        } catch (IOException e) {
            log.warn("Policy attributes load failed: {} (returning empty map)", storagePath, e);
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
                log.debug("Policy mapping storage file deleted: {}", storagePath);
            } else {
                log.warn("Policy mapping storage file deletion failed: {}", storagePath);
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
        private static final int CURRENT_STORAGE_SCHEMA_VERSION = 2;  // v2: policyAttributes 추가

        private int storageSchemaVersion = CURRENT_STORAGE_SCHEMA_VERSION;  // 저장소 포맷 버전
        private long timestamp;
        private Map<String, String> mappings; // 테이블.컬럼 → 정책명 (null 가능)
        private Long version;
        private Map<String, PolicyAttributesData> policyAttributes; // v2: policyName → 속성
        
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

        public Map<String, PolicyAttributesData> getPolicyAttributes() {
            return policyAttributes;
        }

        public void setPolicyAttributes(Map<String, PolicyAttributesData> policyAttributes) {
            this.policyAttributes = policyAttributes;
        }
    }

    /**
     * 정책 속성 데이터 (영구 저장용)
     */
    public static class PolicyAttributesData {
        private Boolean useIv;
        private Boolean usePlain;

        public Boolean getUseIv() {
            return useIv;
        }

        public void setUseIv(Boolean useIv) {
            this.useIv = useIv;
        }

        public Boolean getUsePlain() {
            return usePlain;
        }

        public void setUsePlain(Boolean usePlain) {
            this.usePlain = usePlain;
        }
    }
}

