package com.dadp.common.sync.schema;

/**
 * 스키마 메타데이터 DTO (공통)
 * 
 * AOP와 Wrapper 모두에서 사용하는 공통 스키마 메타데이터 DTO입니다.
 * 
 * @author DADP Development Team
 * @version 5.0.9
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
    private String policyName;      // AOP에서 사용 (Wrapper에서는 null 가능)
    
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
}

