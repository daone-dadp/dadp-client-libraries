package com.dadp.jdbc.policy;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

/**
 * 스키마 인식기
 * 
 * DB 메타데이터를 조회하여 테이블/컬럼 정보를 수집합니다.
 * 
 * @author DADP Development Team
 * @version 5.4.0
 * @since 2025-11-07
 */
public class SchemaRecognizer {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(SchemaRecognizer.class);
    
    /**
     * 스키마 메타데이터 수집 (기본 설정 사용)
     * 
     * @param connection DB 연결
     * @param datasourceId Datasource ID (Hub에서 받은 논리 데이터소스 ID)
     * @return 스키마 메타데이터 목록
     */
    public List<SchemaMetadata> collectSchemaMetadata(Connection connection, String datasourceId) throws SQLException {
        return collectSchemaMetadata(connection, datasourceId, null, -1, null);
    }
    
    /**
     * 스키마 메타데이터 수집 (안정성 설정 포함)
     * 
     * @param connection DB 연결
     * @param datasourceId Datasource ID (Hub에서 받은 논리 데이터소스 ID)
     * @param schemaAllowlist 허용 스키마 목록 (쉼표로 구분, null이면 모든 스키마 허용)
     * @param maxSchemas 최대 스키마 개수 (-1이면 제한 없음)
     * @param timeoutMs 타임아웃 (밀리초, -1이면 제한 없음)
     * @return 스키마 메타데이터 목록
     * @throws SQLException 타임아웃 또는 최대 개수 초과 시
     */
    public List<SchemaMetadata> collectSchemaMetadata(Connection connection, String datasourceId,
                                                      String schemaAllowlist, int maxSchemas, Long timeoutMs) throws SQLException {
        List<SchemaMetadata> schemas = new ArrayList<>();
        
        // 시스템 스키마 제외 목록 (MySQL, PostgreSQL 등 공통)
        final String[] EXCLUDED_SCHEMAS = {
            "information_schema", "performance_schema", "sys", "mysql", 
            "pg_catalog", "pg_toast", "pg_temp_1", "pg_toast_temp_1"
        };
        
        // Allowlist 파싱
        Set<String> allowedSchemas = null;
        if (schemaAllowlist != null && !schemaAllowlist.trim().isEmpty()) {
            allowedSchemas = new HashSet<>();
            String[] parts = schemaAllowlist.split(",");
            for (String part : parts) {
                String trimmed = part.trim().toLowerCase();
                if (!trimmed.isEmpty()) {
                    allowedSchemas.add(trimmed);
                }
            }
            log.info("📋 스키마 Allowlist 적용: {}", allowedSchemas);
        }
        
        // 시작 시간 기록 (타임아웃 체크용)
        long startTime = System.currentTimeMillis();
        
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String dbVendor = metaData.getDatabaseProductName().toLowerCase();
            String databaseName = connection.getCatalog();
            
            // DB 벤더별 schemaName 추출
            String schemaName = extractSchemaName(connection, dbVendor);
            
            log.info("🔍 스키마 메타데이터 수집 시작: datasourceId={}, dbVendor={}, database={}, schemaName={}, " +
                    "allowlist={}, maxSchemas={}, timeout={}ms", 
                datasourceId, dbVendor, databaseName, schemaName,
                allowedSchemas != null ? allowedSchemas : "모두 허용",
                maxSchemas > 0 ? maxSchemas : "제한 없음",
                timeoutMs != null && timeoutMs > 0 ? timeoutMs : "제한 없음");
            
            // 현재 데이터베이스의 테이블만 조회 (시스템 스키마 제외)
            try (ResultSet tables = metaData.getTables(databaseName, null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    // 타임아웃 체크
                    if (timeoutMs != null && timeoutMs > 0) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        if (elapsed > timeoutMs) {
                            log.warn("⏱️ 스키마 수집 타임아웃: {}ms 경과 (제한: {}ms), 현재까지 {}개 스키마 수집", 
                                elapsed, timeoutMs, schemas.size());
                            throw new SQLException("스키마 수집 타임아웃: " + elapsed + "ms 경과 (제한: " + timeoutMs + "ms)");
                        }
                    }
                    
                    // 최대 개수 체크
                    if (maxSchemas > 0 && schemas.size() >= maxSchemas) {
                        log.warn("📊 최대 스키마 개수 초과: {}개 (제한: {}개), 수집 중단", schemas.size(), maxSchemas);
                        break;
                    }
                    
                    String tableName = tables.getString("TABLE_NAME");
                    String tableSchema = tables.getString("TABLE_SCHEM");
                    
                    // 시스템 스키마 제외
                    if (tableSchema != null) {
                        String lowerSchema = tableSchema.toLowerCase();
                        boolean isExcluded = false;
                        for (String excluded : EXCLUDED_SCHEMAS) {
                            if (lowerSchema.equals(excluded)) {
                                isExcluded = true;
                                log.trace("⏭️ 시스템 스키마 제외: {}.{}", tableSchema, tableName);
                                break;
                            }
                        }
                        if (isExcluded) {
                            continue;
                        }
                        
                        // Allowlist 필터링
                        if (allowedSchemas != null && !allowedSchemas.contains(lowerSchema)) {
                            log.trace("⏭️ Allowlist에 없는 스키마 제외: {}.{}", tableSchema, tableName);
                            continue;
                        }
                    }
                    
                    log.trace("📋 테이블 발견: {}.{}", tableSchema, tableName);
                    
                    // 컬럼 정보 조회
                    try (ResultSet columns = metaData.getColumns(databaseName, tableSchema, tableName, "%")) {
                        while (columns.next()) {
                            String columnName = columns.getString("COLUMN_NAME");
                            String columnType = columns.getString("TYPE_NAME");
                            String columnDefault = columns.getString("COLUMN_DEF");
                            String isAutoIncrement = columns.getString("IS_AUTOINCREMENT");
                            
                            // 암복호화 대상에서 제외할 컬럼 필터링
                            if (shouldExcludeColumn(columnName, columnType, columnDefault, isAutoIncrement)) {
                                log.trace("   ⏭️ 제외: {} ({}) - 암복호화 대상 아님", columnName, columnType);
                                continue;
                            }
                            
                            SchemaMetadata schema = new SchemaMetadata();
                            schema.setDatasourceId(datasourceId);
                            schema.setDbVendor(dbVendor);
                            schema.setDatabaseName(databaseName);
                            schema.setSchemaName(schemaName != null ? schemaName : tableSchema);
                            schema.setTableName(tableName);
                            schema.setColumnName(columnName);
                            schema.setColumnType(columnType);
                            schema.setIsNullable("YES".equals(columns.getString("IS_NULLABLE")));
                            schema.setColumnDefault(columnDefault);
                            
                            schemas.add(schema);
                            
                            log.trace("   └─ 컬럼: {} ({})", schema.getColumnName(), schema.getColumnType());
                        }
                    }
                }
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("✅ 스키마 메타데이터 수집 완료: {}개 컬럼 (소요 시간: {}ms)", schemas.size(), elapsed);
            
            // 최대 개수 초과 경고
            if (maxSchemas > 0 && schemas.size() >= maxSchemas) {
                log.warn("⚠️ 최대 스키마 개수에 도달: {}개 (제한: {}개)", schemas.size(), maxSchemas);
            }
            
        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("❌ 스키마 메타데이터 수집 실패: {} (소요 시간: {}ms, 수집된 스키마: {}개)", 
                e.getMessage(), elapsed, schemas.size(), e);
            throw e;
        }
        
        return schemas;
    }
    
    /**
     * DB 벤더별 schemaName 추출
     * 
     * @param connection DB 연결
     * @param dbVendor DB 벤더명 (소문자)
     * @return DADP 기준 논리 스키마명
     */
    private String extractSchemaName(Connection connection, String dbVendor) throws SQLException {
        if (dbVendor.contains("mysql") || dbVendor.contains("mariadb")) {
            // MySQL: database == schema
            return connection.getCatalog();
            
        } else if (dbVendor.contains("postgresql")) {
            // PostgreSQL: database + schema
            String schema = connection.getSchema();
            if (schema == null || schema.isEmpty()) {
                schema = "public";  // 기본 스키마
            }
            return schema;
            
        } else if (dbVendor.contains("microsoft sql server") || dbVendor.contains("sql server")) {
            // MSSQL: database + schema
            // TABLE_SCHEMA를 ResultSet에서 읽어야 함
            // 여기서는 기본값 "dbo" 반환, 실제로는 ResultSet에서 읽음
            return "dbo";  // 기본값
            
        } else if (dbVendor.contains("oracle")) {
            // Oracle: USER(OWNER)가 사실상 schema
            String schema = connection.getSchema();
            if (schema == null || schema.isEmpty()) {
                try {
                    schema = connection.getMetaData().getUserName();
                } catch (SQLException e) {
                    log.debug("Oracle userName 조회 실패: {}", e.getMessage());
                }
            }
            return schema;
        }
        
        // 기본값: database 이름
        return connection.getCatalog();
    }
    
    /**
     * 암복호화 대상에서 제외할 컬럼인지 확인
     * 
     * @param columnName 컬럼명
     * @param columnType 컬럼 타입
     * @param columnDefault 기본값
     * @param isAutoIncrement 자동 증가 여부
     * @return 제외 여부 (true: 제외, false: 포함)
     */
    private boolean shouldExcludeColumn(String columnName, String columnType, 
                                       String columnDefault, String isAutoIncrement) {
        if (columnName == null || columnType == null) {
            return false;
        }
        
        String lowerColumnName = columnName.toLowerCase();
        String lowerColumnType = columnType.toLowerCase();
        String lowerDefault = columnDefault != null ? columnDefault.toLowerCase() : "";
        
        // 1. 자동 증가 컬럼 제외 (AUTO_INCREMENT)
        if ("YES".equalsIgnoreCase(isAutoIncrement)) {
            return true;
        }
        
        // 2. 날짜/시간 타입 제외
        if (lowerColumnType.contains("date") || 
            lowerColumnType.contains("time") || 
            lowerColumnType.contains("timestamp") ||
            lowerColumnType.equals("year")) {
            return true;
        }
        
        // 3. UUID/GUID 타입 제외
        if (lowerColumnType.contains("uuid") || 
            lowerColumnType.contains("guid") ||
            lowerColumnType.contains("uniqueidentifier")) {
            return true;
        }
        
        // 4. ID/UID 컬럼명 패턴 제외 (id, uid, uuid, guid 등)
        if (lowerColumnName.equals("id") || 
            lowerColumnName.equals("uid") ||
            lowerColumnName.equals("uuid") ||
            lowerColumnName.equals("guid") ||
            lowerColumnName.endsWith("_id") ||
            lowerColumnName.endsWith("_uid") ||
            lowerColumnName.endsWith("_uuid")) {
            return true;
        }
        
        // 5. 자동 생성 타임스탬프 컬럼 제외 (created_at, updated_at 등)
        if ((lowerColumnName.equals("created_at") || 
             lowerColumnName.equals("updated_at") ||
             lowerColumnName.equals("deleted_at") ||
             lowerColumnName.equals("modified_at")) &&
            (lowerColumnType.contains("timestamp") || 
             lowerColumnType.contains("datetime") ||
             lowerDefault.contains("current_timestamp") ||
             lowerDefault.contains("now()"))) {
            return true;
        }
        
        // 6. 기본값이 자동 생성되는 컬럼 제외 (CURRENT_TIMESTAMP, NOW() 등)
        if (lowerDefault.contains("current_timestamp") ||
            lowerDefault.contains("now()") ||
            lowerDefault.contains("gen_random_uuid()") ||
            lowerDefault.contains("uuid_generate_v4()")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 스키마 메타데이터 DTO
     */
    public static class SchemaMetadata {
        private String datasourceId;    // NEW: Hub에서 받은 논리 데이터소스 ID
        private String dbVendor;        // NEW: "mysql", "postgresql", "mssql", "oracle"
        private String databaseName;    // 유지: catalog (필요시)
        private String schemaName;      // NEW: DADP 기준 논리 스키마명
        private String tableName;
        private String columnName;
        private String columnType;
        private Boolean isNullable;
        private String columnDefault;
        
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
    }
}
