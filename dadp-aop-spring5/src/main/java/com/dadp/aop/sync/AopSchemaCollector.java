package com.dadp.aop.sync;

import com.dadp.aop.metadata.EncryptionMetadataInitializer;
import com.dadp.common.sync.schema.SchemaCollector;
import com.dadp.common.sync.schema.SchemaMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AOP 기반 스키마 수집 구현체 (AOP용)
 * 
 * EncryptionMetadataInitializer에서 수집한 암호화 필드 정보를 공통 SchemaMetadata로 변환합니다.
 * 
 * @author DADP Development Team
 * @version 5.2.0
 * @since 2026-01-07
 */
public class AopSchemaCollector implements SchemaCollector {
    
    private final EncryptionMetadataInitializer metadataInitializer;
    
    public AopSchemaCollector(EncryptionMetadataInitializer metadataInitializer) {
        this.metadataInitializer = metadataInitializer;
    }
    
    @Override
    public List<SchemaMetadata> collectSchemas() throws Exception {
        if (metadataInitializer == null) {
            return new ArrayList<SchemaMetadata>();
        }
        
        // EncryptionMetadataInitializer에서 암호화 필드 정보 수집
        Map<String, String> encryptedColumns = metadataInitializer.getAllEncryptedColumns();
        
        // 공통 SchemaMetadata로 변환
        List<SchemaMetadata> schemas = new ArrayList<SchemaMetadata>();
        for (Map.Entry<String, String> entry : encryptedColumns.entrySet()) {
            String key = entry.getKey(); // "table.column" 형식
            String[] parts = key.split("\\.", 2);
            if (parts.length == 2) {
                String tableName = parts[0];
                String columnName = parts[1];
                // schemaName은 기본값 "public" 사용 (AOP는 스키마 개념이 없음)
                String schemaName = "public";
                
                SchemaMetadata schema = new SchemaMetadata();
                schema.setSchemaName(schemaName);
                schema.setTableName(tableName);
                schema.setColumnName(columnName);
                schema.setPolicyName("dadp");  // 기본값
                // Wrapper 전용 필드는 null
                schema.setDatasourceId(null);
                schema.setDbVendor(null);
                schema.setDatabaseName(null);
                schema.setColumnType(null);
                schema.setIsNullable(null);
                schema.setColumnDefault(null);
                schemas.add(schema);
            }
        }
        
        return schemas;
    }
}

