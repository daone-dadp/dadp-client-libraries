package com.dadp.common.sync.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dadp.common.logging.DadpLogger;
import com.dadp.common.logging.DadpLoggerFactory;

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
    
    private static final String DEFAULT_STORAGE_DIR = System.getProperty("user.home") + "/.dadp-wrapper";
    private static final String DEFAULT_STORAGE_FILE = "schemas.json";
    
    private final String storagePath;
    private final ObjectMapper objectMapper;
    
    /**
     * 기본 생성자 (사용자 홈 디렉토리 사용)
     */
    public SchemaStorage() {
        this(DEFAULT_STORAGE_DIR, DEFAULT_STORAGE_FILE);
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
        if (finalStoragePath != null) {
            log.info("✅ 스키마 저장소 초기화: {}", this.storagePath);
        } else {
            log.warn("⚠️ 스키마 저장소 초기화 실패: 저장 불가");
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
            log.warn("⚠️ 저장 경로가 설정되지 않아 스키마 저장 불가");
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
            
            log.info("💾 스키마 메타데이터 저장 완료: {}개 스키마 → {}", 
                    schemas != null ? schemas.size() : 0, storagePath);
            return true;
            
        } catch (IOException e) {
            log.error("❌ 스키마 메타데이터 저장 실패: {}", storagePath, e);
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
            log.warn("⚠️ 저장 경로가 설정되지 않아 스키마 로드 불가");
            return new ArrayList<>();
        }
        
        File storageFile = new File(storagePath);
        if (!storageFile.exists()) {
            log.debug("📋 스키마 저장 파일이 없음: {} (새로 생성될 예정)", storagePath);
            return new ArrayList<>();
        }
        
        try {
            SchemaData data = objectMapper.readValue(storageFile, SchemaData.class);
            
            if (data == null || data.getSchemas() == null) {
                log.warn("⚠️ 스키마 데이터가 비어있음: {}", storagePath);
                return new ArrayList<>();
            }
            
            List<SchemaMetadata> schemas = data.getSchemas();
            long timestamp = data.getTimestamp();
            
            log.info("📂 스키마 메타데이터 로드 완료: {}개 스키마 (저장 시각: {})", 
                    schemas.size(), new java.util.Date(timestamp));
            return schemas;
            
        } catch (IOException e) {
            log.warn("⚠️ 스키마 메타데이터 로드 실패: {} (빈 리스트 반환)", storagePath, e);
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
                log.info("🗑️ 스키마 저장 파일 삭제 완료: {}", storagePath);
            } else {
                log.warn("⚠️ 스키마 저장 파일 삭제 실패: {}", storagePath);
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
            log.warn("⚠️ 저장 경로가 설정되지 않아 정책명 업데이트 불가");
            return 0;
        }
        
        List<SchemaMetadata> schemas = loadSchemas();
        if (schemas.isEmpty()) {
            log.debug("📋 업데이트할 스키마가 없음");
            return 0;
        }
        
        int updatedCount = 0;
        for (SchemaMetadata schema : schemas) {
            if (schema == null) {
                continue;
            }
            
            // 키 생성: schema.table.column
            String key = (schema.getSchemaName() != null ? schema.getSchemaName() : "") + "." +
                         (schema.getTableName() != null ? schema.getTableName() : "") + "." +
                         (schema.getColumnName() != null ? schema.getColumnName() : "");
            
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
            log.info("💾 스키마 정책명 업데이트 완료: {}개 스키마 업데이트", updatedCount);
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
            log.warn("⚠️ 저장 경로가 설정되지 않아 스키마 상태 업데이트 불가");
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
            log.debug("💾 스키마 상태 업데이트: key={}, status={}", schemaKey, newStatus);
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
            log.info("💾 스키마 상태 일괄 업데이트: {}개 스키마, status={}", updatedCount, newStatus);
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
            log.info("💾 스키마 비교 및 상태 업데이트 완료: {}개 스키마 업데이트", updatedCount);
        }
        
        return updatedCount;
    }
    
    /**
     * 스키마 데이터 구조
     */
    public static class SchemaData {
        private long timestamp;
        private List<SchemaMetadata> schemas;
        
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

