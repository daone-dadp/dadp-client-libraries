package com.dadp.common.sync.entity;

import java.util.List;

/**
 * 엔티티 감지 인터페이스
 * 
 * 다양한 방식으로 엔티티를 감지하여 암복호화 대상 필드를 찾습니다.
 * JPA, 리플렉션, 어노테이션 스캔 등 다양한 방식의 구현을 지원합니다.
 * 
 * @author DADP Development Team
 * @version 5.4.0
 * @since 2026-01-09
 */
public interface EntityDetector {
    
    /**
     * 엔티티 목록 감지
     * 
     * @return 감지된 엔티티 메타데이터 목록
     */
    List<EntityMetadata> detectEntities();
    
    /**
     * 감지기 타입 반환
     * 
     * @return 감지기 타입 ("jpa", "reflection", "annotation" 등)
     */
    String getDetectorType();
    
    /**
     * 감지 가능 여부 확인
     * 
     * @return 감지 가능 여부
     */
    default boolean canDetect() {
        return true;
    }
    
    /**
     * 엔티티 메타데이터
     * 
     * 엔티티 클래스와 암복호화 대상 필드 정보를 담습니다.
     */
    class EntityMetadata {
        private Class<?> entityClass;
        private String tableName;
        private String schemaName;
        private List<FieldMetadata> fields;
        
        public EntityMetadata(Class<?> entityClass, String tableName, String schemaName, List<FieldMetadata> fields) {
            this.entityClass = entityClass;
            this.tableName = tableName;
            this.schemaName = schemaName;
            this.fields = fields;
        }
        
        public Class<?> getEntityClass() {
            return entityClass;
        }
        
        public String getTableName() {
            return tableName;
        }
        
        public String getSchemaName() {
            return schemaName;
        }
        
        public List<FieldMetadata> getFields() {
            return fields;
        }
    }
    
    /**
     * 필드 메타데이터
     * 
     * 암복호화 대상 필드의 정보를 담습니다.
     */
    class FieldMetadata {
        private String fieldName;
        private String columnName;
        private String policyName;
        private String columnType;
        
        public FieldMetadata(String fieldName, String columnName, String policyName) {
            this(fieldName, columnName, policyName, null);
        }
        
        public FieldMetadata(String fieldName, String columnName, String policyName, String columnType) {
            this.fieldName = fieldName;
            this.columnName = columnName;
            this.policyName = policyName;
            this.columnType = columnType;
        }
        
        public String getFieldName() {
            return fieldName;
        }
        
        public String getColumnName() {
            return columnName;
        }
        
        public String getPolicyName() {
            return policyName;
        }
        
        public String getColumnType() {
            return columnType;
        }
    }
}

