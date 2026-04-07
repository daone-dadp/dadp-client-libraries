package com.dadp.common.sync.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;
import com.dadp.common.sync.config.StoragePathResolver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 스키마 메타데이터 영구 저장소
 * 
 * Hub에 동기화한 스키마 정보를 파일에 저장하고,
 * 재시작 시 스키마 변경 여부를 확인할 수 있도록 합니다.
 * 
 * @author DADP Development Team
 * @version 5.2.0
 * @since 2026-01-07
 */
public class SchemaStorage {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(SchemaStorage.class);
    
    private static final String DEFAULT_STORAGE_FILE = "schemas.json";
    
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
    public SchemaStorage() {
        this(getDefaultStorageDir(), DEFAULT_STORAGE_FILE);
    }
    
    /**
     * instanceId를 사용한 생성자
     * 
     * @param instanceId 인스턴스 ID (별칭, 앱 구동 시점에 알 수 있음)
     */
    public SchemaStorage(String instanceId) {
        this(getDefaultStorageDir(instanceId), DEFAULT_STORAGE_FILE);
    }
    
    /**
     * 커스텀 저장 경로 지정
     * 
     * @param storageDir 저장 디렉토리
     * @param fileName 파일명
     */
    public SchemaStorage(String storageDir, String fileName) {
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
        if (finalStoragePath != null) {
            log.info("Schema storage initialized: {}", this.storagePath);
        } else {
            log.warn("Schema storage initialization failed: storage unavailable");
        }
    }
    
    /**
     * 스키마 메타데이터 저장
     * 
     * @param schemas 스키마 메타데이터 목록
     * @return 저장 성공 여부
     */
    public boolean saveSchemas(List<SchemaMetadata> schemas) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot save schemas");
            return false;
        }

        try {
            // 저장 데이터 구조
            SchemaData data = new SchemaData();
            data.setTimestamp(System.currentTimeMillis());
            data.setSchemas(schemas);

            // 파일에 저장
            File storageFile = new File(storagePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, data);

            log.debug("Schema metadata saved: {} schemas -> {}",
                    schemas != null ? schemas.size() : 0, storagePath);
            return true;

        } catch (IOException e) {
            log.warn("Schema metadata save failed: {}", storagePath, e);
            return false;
        }
    }
    
    /**
     * 스키마 메타데이터 로드
     * 
     * @return 스키마 메타데이터 목록, 로드 실패 시 빈 리스트
     */
    public List<SchemaMetadata> loadSchemas() {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot load schemas");
            return new ArrayList<>();
        }

        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            log.debug("Schema storage file not found: {} (will be created)", storagePath);
            return new ArrayList<>();
        }
        
        try {
            SchemaData data = objectMapper.readValue(storageFile, SchemaData.class);
            
            if (data == null || data.getSchemas() == null) {
                log.warn("Schema data is empty: {}", storagePath);
                return new ArrayList<>();
            }
            
            // 저장소 포맷 버전 확인 및 하위 호환성 처리
            int version = data.getStorageSchemaVersion();
            if (version == 0) {
                // 구버전 포맷 (버전 필드 없음) -> 버전 1로 간주
                log.debug("Legacy schema format detected (no version field) -> treating as version 1");
                version = 1;
            }
            
            // 향후 버전 호환성 체크
            if (version > SchemaData.CURRENT_STORAGE_SCHEMA_VERSION) {
                log.warn("Unknown schema format version: {} (current supported version: {}), " +
                        "proceeding for backward compatibility",
                    version, SchemaData.CURRENT_STORAGE_SCHEMA_VERSION);
            }
            
            List<SchemaMetadata> schemas = data.getSchemas();
            long timestamp = data.getTimestamp();
            
            log.debug("Schema metadata loaded: {} schemas (saved at: {}, format version: {})",
                    schemas.size(), new java.util.Date(timestamp), version);
            return schemas;
            
        } catch (IOException e) {
            log.warn("Schema metadata load failed: {} (returning empty list)", storagePath, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 저장 파일 존재 여부 확인
     * 
     * @return 파일 존재 여부
     */
    public boolean hasStoredSchemas() {
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
                log.debug("Schema storage file deleted: {}", storagePath);
            } else {
                log.warn("Schema storage file deletion failed: {}", storagePath);
            }
            return deleted;
        }
        return true; // 파일이 없으면 성공으로 간주
    }
    
    /**
     * 저장된 스키마의 정책명 업데이트
     * 
     * @param policyMappings 정책 매핑 맵 (schema.table.column → policyName)
     * @return 업데이트된 스키마 개수
     */
    public int updatePolicyNames(Map<String, String> policyMappings) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot update policy names");
            return 0;
        }

        List<SchemaMetadata> schemas = loadSchemas();
        if (schemas.isEmpty()) {
            log.debug("No schemas to update");
            return 0;
        }
        
        int updatedCount = 0;
        for (SchemaMetadata schema : schemas) {
            if (schema == null) {
                continue;
            }
            
            // 키 생성: getKey() 메서드 사용 (datasourceId 고려)
            // Wrapper: datasourceId:schema.table.column
            // AOP: schema.table.column
            String key = schema.getKey();
            
            // 정책 매핑에서 정책명 찾기
            String policyName = policyMappings.get(key);
            if (policyName != null) {
                schema.setPolicyName(policyName);
                updatedCount++;
            } else {
                // 정책 매핑에 없으면 null로 설정 (정책 제거)
                schema.setPolicyName(null);
            }
        }
        
        // 업데이트된 스키마 저장
        if (updatedCount > 0 || !policyMappings.isEmpty()) {
            saveSchemas(schemas);
            log.debug("Schema policy names updated: {} schemas updated", updatedCount);
        }
        
        return updatedCount;
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
     * 생성 상태의 스키마 조회
     * 
     * @return 생성 상태의 스키마 목록
     */
    public List<SchemaMetadata> getCreatedSchemas() {
        List<SchemaMetadata> allSchemas = loadSchemas();
        List<SchemaMetadata> createdSchemas = new ArrayList<>();
        for (SchemaMetadata schema : allSchemas) {
            if (schema != null) {
                String status = schema.getStatus();
                // 구버전 스키마는 status가 null일 수 있음 -> CREATED로 처리
                if (status == null || status.trim().isEmpty() || 
                    SchemaMetadata.Status.CREATED.equals(status)) {
                    createdSchemas.add(schema);
                }
            }
        }
        return createdSchemas;
    }
    
    /**
     * 스키마 상태 업데이트
     * 
     * @param schemaKey 스키마 키 (schema.table.column)
     * @param newStatus 새로운 상태
     * @return 업데이트 성공 여부
     */
    public boolean updateSchemaStatus(String schemaKey, String newStatus) {
        if (storagePath == null) {
            log.warn("Storage path not set, cannot update schema status");
            return false;
        }
        
        List<SchemaMetadata> schemas = loadSchemas();
        boolean updated = false;
        
        for (SchemaMetadata schema : schemas) {
            if (schema != null && schemaKey.equals(schema.getKey())) {
                schema.setStatus(newStatus);
                updated = true;
                break;
            }
        }
        
        if (updated) {
            saveSchemas(schemas);
            log.debug("Schema status updated: key={}, status={}", schemaKey, newStatus);
        }
        
        return updated;
    }
    
    /**
     * 여러 스키마의 상태를 일괄 업데이트
     * 
     * @param schemaKeys 스키마 키 목록
     * @param newStatus 새로운 상태
     * @return 업데이트된 스키마 개수
     */
    public int updateSchemasStatus(List<String> schemaKeys, String newStatus) {
        if (storagePath == null || schemaKeys == null || schemaKeys.isEmpty()) {
            return 0;
        }
        
        List<SchemaMetadata> schemas = loadSchemas();
        int updatedCount = 0;
        
        for (SchemaMetadata schema : schemas) {
            if (schema != null && schemaKeys.contains(schema.getKey())) {
                schema.setStatus(newStatus);
                updatedCount++;
            }
        }
        
        if (updatedCount > 0) {
            saveSchemas(schemas);
            log.debug("Schema status batch updated: {} schemas, status={}", updatedCount, newStatus);
        }
        
        return updatedCount;
    }
    
    /**
     * 스키마 비교 및 상태 업데이트
     * 
     * @param currentSchemas 현재 로드된 스키마 목록
     * @return 업데이트된 스키마 개수
     */
    public int compareAndUpdateSchemas(List<SchemaMetadata> currentSchemas) {
        if (storagePath == null) {
            return 0;
        }
        
        List<SchemaMetadata> storedSchemas = loadSchemas();
        Map<String, SchemaMetadata> storedMap = new java.util.HashMap<>();
        for (SchemaMetadata schema : storedSchemas) {
            if (schema != null) {
                storedMap.put(schema.getKey(), schema);
            }
        }
        
        Map<String, SchemaMetadata> currentMap = new java.util.HashMap<>();
        for (SchemaMetadata schema : currentSchemas) {
            if (schema != null) {
                currentMap.put(schema.getKey(), schema);
            }
        }
        
        int updatedCount = 0;
        List<SchemaMetadata> updatedSchemas = new ArrayList<>();
        
        // 1. 현재 스키마 처리
        for (SchemaMetadata currentSchema : currentSchemas) {
            if (currentSchema == null) {
                continue;
            }
            
            String key = currentSchema.getKey();
            SchemaMetadata storedSchema = storedMap.get(key);
            
            if (storedSchema == null) {
                // 새로운 스키마 -> CREATED 상태로 저장
                currentSchema.setStatus(SchemaMetadata.Status.CREATED);
                updatedSchemas.add(currentSchema);
                updatedCount++;
            } else {
                // 기존 스키마
                String storedStatus = storedSchema.getStatus();
                
                // 구버전 스키마는 status가 null일 수 있음 -> CREATED로 설정
                if (storedStatus == null || storedStatus.trim().isEmpty()) {
                    storedSchema.setStatus(SchemaMetadata.Status.CREATED);
                    storedSchema.setPolicyName(currentSchema.getPolicyName());
                    updatedSchemas.add(storedSchema);
                    updatedCount++;
                } else if (SchemaMetadata.Status.REGISTERED.equals(storedStatus)) {
                    // 등록 상태 -> 그대로 유지
                    storedSchema.setPolicyName(currentSchema.getPolicyName());
                    updatedSchemas.add(storedSchema);
                } else if (SchemaMetadata.Status.DELETED.equals(storedStatus)) {
                    // 삭제 상태 -> CREATED로 변경 (재로드됨)
                    currentSchema.setStatus(SchemaMetadata.Status.CREATED);
                    updatedSchemas.add(currentSchema);
                    updatedCount++;
                } else {
                    // CREATED 상태 -> 그대로 유지
                    storedSchema.setPolicyName(currentSchema.getPolicyName());
                    updatedSchemas.add(storedSchema);
                }
            }
        }
        
        // 2. 저장소에는 있으나 현재 로드에는 없는 스키마 -> DELETED 상태
        for (Map.Entry<String, SchemaMetadata> entry : storedMap.entrySet()) {
            String key = entry.getKey();
            if (!currentMap.containsKey(key)) {
                SchemaMetadata deletedSchema = entry.getValue();
                if (!SchemaMetadata.Status.DELETED.equals(deletedSchema.getStatus())) {
                    deletedSchema.setStatus(SchemaMetadata.Status.DELETED);
                    updatedSchemas.add(deletedSchema);
                    updatedCount++;
                }
            }
        }
        
        if (updatedCount > 0 || !updatedSchemas.isEmpty()) {
            saveSchemas(updatedSchemas);
            log.debug("Schema comparison and status update completed: {} schemas updated", updatedCount);
        }
        
        return updatedCount;
    }
    
    /**
     * 스키마 데이터 구조
     */
    public static class SchemaData {
        private static final int CURRENT_STORAGE_SCHEMA_VERSION = 1;  // 현재 저장소 포맷 버전
        
        private int storageSchemaVersion = CURRENT_STORAGE_SCHEMA_VERSION;  // 저장소 포맷 버전
        private long timestamp;
        private List<SchemaMetadata> schemas;
        
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
        
        public List<SchemaMetadata> getSchemas() {
            return schemas;
        }
        
        public void setSchemas(List<SchemaMetadata> schemas) {
            this.schemas = schemas;
        }
    }
}

