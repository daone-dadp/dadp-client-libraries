package com.dadp.common.sync.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 스키마 메타데이터 DTO (공통)
 * 
 * AOP와 Wrapper 모두에서 사용하는 공통 스키마 메타데이터 DTO입니다.
 * 
 * @author DADP Development Team
 * @version 5.2.2
 * @since 2026-01-06
 */
public class SchemaMetadata {
    private String datasourceId;    // Hub에서 받은 논리 데이터소스 ID (Wrapper에서 사용)
    private String dbVendor;        // "mysql", "postgresql", "mssql", "oracle" (Wrapper에서 사용)
    private String databaseName;    // catalog (필요시)
    private String schemaName;      // DADP 기준 논리 스키마명
    private String tableName;
    private String columnName;
    private String columnType;      // Wrapper에서 사용 (AOP에서는 null 가능)
    private Boolean isNullable;     // Wrapper에서 사용 (AOP에서는 null 가능)
    private String columnDefault;   // Wrapper에서 사용 (AOP에서는 null 가능)
    
    @JsonIgnore
    private String policyName;      // AOP에서 사용 (Wrapper에서는 null 가능) - Hub 전송 시 제외
    @JsonIgnore
    private String status;          // 스키마 상태: "CREATED", "REGISTERED", "DELETED" - Hub 전송 시 제외
    
    /**
     * 스키마 상태 열거형
     */
    public static class Status {
        public static final String CREATED = "CREATED";      // 생성됨 (Hub에 전송 전)
        public static final String REGISTERED = "REGISTERED"; // 등록됨 (Hub에 전송 완료)
        public static final String DELETED = "DELETED";      // 삭제됨 (로드에서 제거됨)
    }
    
    // Getters and Setters
    public String getDatasourceId() {
        return datasourceId;
    }
    
    public void setDatasourceId(String datasourceId) {
        this.datasourceId = datasourceId;
    }
    
    public String getDbVendor() {
        return dbVendor;
    }
    
    public void setDbVendor(String dbVendor) {
        this.dbVendor = dbVendor;
    }
    
    public String getDatabaseName() {
        return databaseName;
    }
    
    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }
    
    public String getSchemaName() {
        return schemaName;
    }
    
    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }
    
    public String getTableName() {
        return tableName;
    }
    
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
    
    public String getColumnName() {
        return columnName;
    }
    
    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }
    
    public String getColumnType() {
        return columnType;
    }
    
    public void setColumnType(String columnType) {
        this.columnType = columnType;
    }
    
    public Boolean getIsNullable() {
        return isNullable;
    }
    
    public void setIsNullable(Boolean isNullable) {
        this.isNullable = isNullable;
    }
    
    public String getColumnDefault() {
        return columnDefault;
    }
    
    public void setColumnDefault(String columnDefault) {
        this.columnDefault = columnDefault;
    }
    
    public String getPolicyName() {
        return policyName;
    }
    
    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    /**
     * 스키마 키 생성 (datasourceId:schema.table.column 또는 schema.table.column)
     * 
     * Wrapper: datasourceId가 있으면 "datasourceId:schema.table.column" 형식
     * AOP: datasourceId가 없으면 "schema.table.column" 형식
     * 
     * JSON 직렬화에서 제외
     */
    @JsonIgnore
    public String getKey() {
        String effectiveSchemaName = schemaName;
        if (datasourceId != null && !datasourceId.trim().isEmpty()) {
            // Wrapper: datasourceId:schema.table.column
            effectiveSchemaName = datasourceId + ":" + (schemaName != null ? schemaName : "");
        }
        return (effectiveSchemaName != null ? effectiveSchemaName : "") + "." +
               (tableName != null ? tableName : "") + "." +
               (columnName != null ? columnName : "");
    }
}

