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
 * @version 5.5.0
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
        
        // Oracle 시스템 스키마 제외 목록
        final String[] ORACLE_EXCLUDED_SCHEMAS = {
            "sys", "system", "ctxsys", "mdsys", "xdb", "olapsys", "ordsys",
            "outln", "si_informtn_schema", "sysaux", "wmsys", "apex_030200",
            "apex_040000", "apex_040100", "apex_040200", "apex_050000",
            "apex_180100", "apex_190100", "apex_200100", "apex_210100",
            "apex_220100", "apex_230100", "apex_240100", "apex_250100",
            "flows_files", "flows_030000", "flows_040000", "flows_040100",
            "flows_040200", "flows_050000", "flows_180100", "flows_190100",
            "flows_200100", "flows_210100", "flows_220100", "flows_230100",
            "flows_240100", "flows_250100"
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
            log.debug("Schema Allowlist applied: {}", allowedSchemas);
        }
        
        // 시작 시간 기록 (타임아웃 체크용)
        long startTime = System.currentTimeMillis();
        
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String dbVendor = metaData.getDatabaseProductName().toLowerCase();
            String databaseName = connection.getCatalog();
            
            // Oracle: 자기 스키마(OWNER)만 조회 (ALL_TABLES 대신 USER_TABLES 효과)
            // 다른 DB: null (전체 스키마 조회 후 필터링)
            String schemaPattern = null;
            if (dbVendor.contains("oracle")) {
                schemaPattern = connection.getSchema();
                if (schemaPattern == null || schemaPattern.isEmpty()) {
                    schemaPattern = metaData.getUserName();
                }
                log.debug("Oracle self-schema only query: schema={}", schemaPattern);
            }

            log.info("Schema metadata collection starting: datasourceId={}, dbVendor={}, database={}, schemaPattern={}, " +
                    "allowlist={}, maxSchemas={}, timeout={}ms",
                datasourceId, dbVendor, databaseName, schemaPattern,
                allowedSchemas != null ? allowedSchemas : "all allowed",
                maxSchemas > 0 ? maxSchemas : "unlimited",
                timeoutMs != null && timeoutMs > 0 ? timeoutMs : "unlimited");

            // 테이블 조회 (Oracle은 자기 스키마만, 다른 DB는 전체 후 필터링)
            try (ResultSet tables = metaData.getTables(databaseName, schemaPattern, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    // 타임아웃 체크
                    if (timeoutMs != null && timeoutMs > 0) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        if (elapsed > timeoutMs) {
                            log.warn("Schema collection timeout: {}ms elapsed (limit: {}ms), {} schemas collected so far",
                                elapsed, timeoutMs, schemas.size());
                            throw new SQLException("Schema collection timeout: " + elapsed + "ms elapsed (limit: " + timeoutMs + "ms)");
                        }
                    }
                    
                    // 최대 개수 체크
                    if (maxSchemas > 0 && schemas.size() >= maxSchemas) {
                        log.warn("Max schema count exceeded: {} (limit: {}), collection stopped", schemas.size(), maxSchemas);
                        break;
                    }
                    
                    String tableName = tables.getString("TABLE_NAME");
                    String tableSchema = tables.getString("TABLE_SCHEM");  // ResultSet에서 실제 스키마 이름 가져옴
                    
                    // 시스템 스키마 제외
                    if (tableSchema != null) {
                        String lowerSchema = tableSchema.toLowerCase();
                        boolean isExcluded = false;
                        
                        // 공통 시스템 스키마 체크
                        for (String excluded : EXCLUDED_SCHEMAS) {
                            if (lowerSchema.equals(excluded)) {
                                isExcluded = true;
                                log.trace("System schema excluded: {}.{}", tableSchema, tableName);
                                break;
                            }
                        }
                        
                        // Oracle 전용 시스템 스키마 체크
                        if (!isExcluded && dbVendor.contains("oracle")) {
                            for (String excluded : ORACLE_EXCLUDED_SCHEMAS) {
                                if (lowerSchema.equals(excluded)) {
                                    isExcluded = true;
                                    log.trace("Oracle system schema excluded: {}.{}", tableSchema, tableName);
                                    break;
                                }
                            }
                            // Oracle: APEX_*, FLOWS_* 패턴으로 시작하는 스키마도 제외
                            if (!isExcluded && (lowerSchema.startsWith("apex_") || lowerSchema.startsWith("flows_"))) {
                                isExcluded = true;
                                log.trace("Oracle system schema excluded (pattern): {}.{}", tableSchema, tableName);
                            }
                        }
                        
                        if (isExcluded) {
                            continue;
                        }
                        
                        // Allowlist 필터링
                        if (allowedSchemas != null && !allowedSchemas.contains(lowerSchema)) {
                            log.trace("Schema not in Allowlist, excluded: {}.{}", tableSchema, tableName);
                            continue;
                        }
                    }
                    
                    log.trace("Table found: {}.{}", tableSchema, tableName);
                    
                    // DB 벤더별로 스키마 이름 결정
                    // PostgreSQL: TABLE_SCHEM에서 가져온 실제 스키마 이름 사용 (예: "public")
                    // MySQL: database 이름 사용 (TABLE_SCHEM은 null일 수 있음)
                    String finalSchemaName = determineSchemaName(dbVendor, tableSchema, connection);
                    
                    // 컬럼 정보 조회
                    try (ResultSet columns = metaData.getColumns(databaseName, tableSchema, tableName, "%")) {
                        while (columns.next()) {
                            String columnName = columns.getString("COLUMN_NAME");
                            String columnType = columns.getString("TYPE_NAME");
                            String columnDefault = columns.getString("COLUMN_DEF");
                            String isAutoIncrement = columns.getString("IS_AUTOINCREMENT");
                            
                            // 암복호화 대상에서 제외할 컬럼 필터링
                            if (shouldExcludeColumn(columnName, columnType, columnDefault, isAutoIncrement)) {
                                log.trace("   Excluded: {} ({}) - not a crypto target", columnName, columnType);
                                continue;
                            }
                            
                            // 식별자 정규화 (암복호화 시와 동일한 방식으로 정규화)
                            // 모든 DB 벤더에 대해 소문자로 정규화 (영구저장소 저장 및 매핑 모두 소문자 기준)
                            String normalizedDatabaseName = normalizeIdentifier(databaseName, dbVendor);
                            String normalizedSchemaName = normalizeIdentifier(finalSchemaName, dbVendor);
                            String normalizedTableName = normalizeIdentifier(tableName, dbVendor);
                            String normalizedColumnName = normalizeIdentifier(columnName, dbVendor);
                            
                            SchemaMetadata schema = new SchemaMetadata();
                            schema.setDatasourceId(datasourceId);
                            schema.setDbVendor(dbVendor);
                            schema.setDatabaseName(normalizedDatabaseName);  // 정규화된 데이터베이스 이름 사용
                            schema.setSchemaName(normalizedSchemaName);  // 정규화된 스키마 이름 사용
                            schema.setTableName(normalizedTableName);  // 정규화된 테이블 이름 사용
                            schema.setColumnName(normalizedColumnName);  // 정규화된 컬럼 이름 사용
                            schema.setColumnType(columnType);
                            schema.setIsNullable("YES".equals(columns.getString("IS_NULLABLE")));
                            schema.setColumnDefault(columnDefault);
                            
                            schemas.add(schema);
                            
                            log.trace("   Column: {} ({})", schema.getColumnName(), schema.getColumnType());
                        }
                    }
                }
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Schema metadata collection completed: {} columns (elapsed: {}ms)", schemas.size(), elapsed);
            
            // 최대 개수 초과 경고
            if (maxSchemas > 0 && schemas.size() >= maxSchemas) {
                log.warn("Max schema count reached: {} (limit: {})", schemas.size(), maxSchemas);
            }
            
        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Schema metadata collection failed: {} (elapsed: {}ms, collected: {})",
                e.getMessage(), elapsed, schemas.size(), e);
            throw e;
        }
        
        return schemas;
    }
    
    /**
     * DB 벤더별로 스키마 이름 결정
     * 
     * @param dbVendor DB 벤더명 (소문자)
     * @param tableSchema ResultSet에서 가져온 TABLE_SCHEM 값 (실제 스키마 이름)
     * @param connection DB 연결
     * @return DADP 기준 논리 스키마명
     */
    private String determineSchemaName(String dbVendor, String tableSchema, Connection connection) throws SQLException {
        if (dbVendor.contains("mysql") || dbVendor.contains("mariadb")) {
            // MySQL: database == schema
            // TABLE_SCHEM은 null일 수 있으므로 database 이름 사용
            if (tableSchema != null && !tableSchema.trim().isEmpty()) {
                return tableSchema;
            }
            return connection.getCatalog();
            
        } else if (dbVendor.contains("postgresql")) {
            // PostgreSQL: TABLE_SCHEM에서 가져온 실제 스키마 이름 사용 (예: "public")
            // getTables()의 ResultSet에서 TABLE_SCHEM 컬럼이 실제 스키마 이름을 반환함
            if (tableSchema != null && !tableSchema.trim().isEmpty()) {
                return tableSchema;
            }
            // fallback: connection.getSchema() 사용
            String schema = connection.getSchema();
            return schema != null && !schema.isEmpty() ? schema : "public";
            
        } else if (dbVendor.contains("microsoft sql server") || dbVendor.contains("sql server")) {
            // MSSQL: TABLE_SCHEM에서 가져온 실제 스키마 이름 사용 (예: "dbo")
            if (tableSchema != null && !tableSchema.trim().isEmpty()) {
                return tableSchema;
            }
            return "dbo";  // 기본값
            
        } else if (dbVendor.contains("oracle")) {
            // Oracle: TABLE_SCHEM에서 가져온 실제 스키마 이름 사용
            if (tableSchema != null && !tableSchema.trim().isEmpty()) {
                return tableSchema;
            }
            // fallback: connection.getSchema() 또는 getUserName() 사용
            String schema = connection.getSchema();
            if (schema == null || schema.isEmpty()) {
                try {
                    schema = connection.getMetaData().getUserName();
                } catch (SQLException e) {
                    log.debug("Failed to retrieve Oracle userName: {}", e.getMessage());
                }
            }
            return schema;
        }
        
        // 기본값: tableSchema가 있으면 사용, 없으면 database 이름
        if (tableSchema != null && !tableSchema.trim().isEmpty()) {
            return tableSchema;
        }
        return connection.getCatalog();
    }
    
    /**
     * DB 벤더별 schemaName 추출 (deprecated: determineSchemaName 사용 권장)
     * 
     * @param connection DB 연결
     * @param dbVendor DB 벤더명 (소문자)
     * @return DADP 기준 논리 스키마명
     * @deprecated determineSchemaName을 사용하세요 (ResultSet의 TABLE_SCHEM을 직접 사용)
     */
    @Deprecated
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
                    log.debug("Failed to retrieve Oracle userName: {}", e.getMessage());
                }
            }
            return schema;
        }
        
        // 기본값: database 이름
        return connection.getCatalog();
    }
    
    /**
     * 식별자 정규화 (스키마 로드 시와 암복호화 시 동일한 키 생성)
     * 
     * Oracle/Tibero의 경우: DatabaseMetaData는 따옴표 없이 생성한 식별자를 대문자로 반환하므로,
     * SQL 파서에서 받은 값도 대문자로 정규화하여 일치시킴
     * 
     * @param identifier 식별자 (schemaName, tableName, columnName)
     * @param dbVendor DB 벤더명
     * @return 정규화된 식별자
     */
    private String normalizeIdentifier(String identifier, String dbVendor) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return identifier;
        }
        
        // 모든 DB 벤더에 대해 소문자로 정규화 (스키마 저장 및 매칭 모두 소문자 기준)
        return identifier.toLowerCase();
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
