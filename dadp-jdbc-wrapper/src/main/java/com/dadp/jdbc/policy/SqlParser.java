package com.dadp.jdbc.policy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

/**
 * SQL 파서
 * 
 * SQL 쿼리를 파싱하여 테이블명, 컬럼명, 파라미터 위치를 추출합니다.
 * 
 * @author DADP Development Team
 * @version 3.0.0
 * @since 2025-11-07
 */
public class SqlParser {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(SqlParser.class);
    
    // INSERT 문 패턴: INSERT INTO [schema.]table (col1, col2, ...) VALUES (?, ?, ...)
    // schema.table 또는 table 형식 모두 지원
    private static final Pattern INSERT_PATTERN = Pattern.compile(
        "INSERT\\s+INTO\\s+(?:([\\w]+)\\.)?([\\w]+)\\s*\\(([^)]+)\\)",
        Pattern.CASE_INSENSITIVE
    );
    
    // UPDATE 문 패턴: UPDATE [schema.]table SET col1 = ?, col2 = ? WHERE ...
    // WHERE 키워드 전까지 매칭 (대소문자 구분 없음)
    // schema.table 또는 table 형식 모두 지원
    private static final Pattern UPDATE_PATTERN = Pattern.compile(
        "UPDATE\\s+(?:([\\w]+)\\.)?([\\w]+)\\s+SET\\s+(.+?)(?:\\s+WHERE|$)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    
    // SELECT 문 패턴: SELECT col1, col2, ... FROM [schema.]table [alias]
    // FROM users u1_0 또는 FROM schema.users u1_0 -> schema와 users 추출
    // 대소문자 구분 없이 FROM 키워드 전까지 매칭
    // schema.table 또는 table 형식 모두 지원
    private static final Pattern SELECT_PATTERN = Pattern.compile(
        "SELECT\\s+(.*?)\\s+FROM\\s+(?:([\\w]+)\\.)?([\\w]+)(?:\\s+\\S+)?",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    
/**
 * SQL 파싱 결과
 */
public static class SqlParseResult {
    private String databaseName;  // 데이터베이스명 (catalog, 필요시)
    private String schemaName;    // NEW: 스키마명 (DADP 기준 논리 스키마명)
    private String tableName;
    private String[] columns;
    private String sqlType; // INSERT, UPDATE, SELECT
    // alias -> 원본 컬럼명 매핑 (Hibernate 지원용)
    private java.util.Map<String, String> aliasToColumnMap = new java.util.HashMap<>();
    
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
    
    public String[] getColumns() {
        return columns;
    }
    
    public void setColumns(String[] columns) {
        this.columns = columns;
    }
    
    public String getSqlType() {
        return sqlType;
    }
    
    public void setSqlType(String sqlType) {
        this.sqlType = sqlType;
    }
    
    /**
     * alias → 원본 컬럼명 매핑 추가
     * 
     * 대소문자 구분 없이 매핑하되, 원본 컬럼명은 그대로 저장
     */
    public void addAliasMapping(String alias, String originalColumn) {
        // alias는 소문자로 변환하여 저장 (대소문자 구분 없이 조회)
        // originalColumn은 원본 그대로 저장 (정규화는 나중에 normalizeIdentifier에서 수행)
        aliasToColumnMap.put(alias.toLowerCase(), originalColumn);
    }
    
    /**
     * alias로 원본 컬럼명 조회
     * @param alias 컬럼 별칭 (예: email3_0_)
     * @return 원본 컬럼명 (원본 그대로 반환), 매핑이 없으면 입력값 반환
     */
    public String getOriginalColumnName(String alias) {
        if (alias == null) return null;
        // alias를 소문자로 변환하여 조회하되, 반환값은 원본 그대로
        String original = aliasToColumnMap.get(alias.toLowerCase());
        return original != null ? original : alias;
    }
    
    /**
     * alias 매핑 존재 여부
     */
    public boolean hasAliasMapping() {
        return !aliasToColumnMap.isEmpty();
    }
}
    
    /**
     * SQL 파싱
     * 
     * @param sql SQL 쿼리
     * @return 파싱 결과
     */
    public SqlParseResult parse(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return null;
        }
        
        String sqlUpper = sql.trim().toUpperCase();
        SqlParseResult result = new SqlParseResult();
        
        // INSERT 문 파싱
        if (sqlUpper.startsWith("INSERT")) {
            result = parseInsert(sql);
        }
        // UPDATE 문 파싱
        else if (sqlUpper.startsWith("UPDATE")) {
            result = parseUpdate(sql);
        }
        // SELECT 문 파싱
        else if (sqlUpper.startsWith("SELECT")) {
            result = parseSelect(sql);
        }
        
        if (result != null && result.getTableName() != null) {
            log.trace("SQL parsed: type={}, table={}, columns={}",
                     result.getSqlType(), result.getTableName(), 
                     result.getColumns() != null ? String.join(", ", result.getColumns()) : "null");
        } else {
            log.debug("SQL parsing failed: sql={}", sql);
        }
        
        return result;
    }
    
    /**
     * INSERT 문 파싱
     * 
     * INSERT INTO schema.table (col1, col2) 또는 INSERT INTO table (col1, col2) 형식 지원
     */
    private SqlParseResult parseInsert(String sql) {
        Matcher matcher = INSERT_PATTERN.matcher(sql);
        if (matcher.find()) {
            SqlParseResult result = new SqlParseResult();
            result.setSqlType("INSERT");
            
            // schema.table 형식 파싱
            String schemaName = matcher.group(1);  // schema (있을 수도 없을 수도 있음)
            String tableName = matcher.group(2);   // table
            
            result.setSchemaName(schemaName);  // schema가 없으면 null
            result.setTableName(tableName);
            
            // 컬럼 목록 추출
            String columnsStr = matcher.group(3);
            String[] columns = columnsStr.split(",");
            for (int i = 0; i < columns.length; i++) {
                columns[i] = columns[i].trim();
            }
            result.setColumns(columns);
            
            return result;
        }
        return null;
    }
    
    /**
     * UPDATE 문 파싱
     * 
     * UPDATE schema.table SET ... 또는 UPDATE table SET ... 형식 지원
     */
    private SqlParseResult parseUpdate(String sql) {
        Matcher matcher = UPDATE_PATTERN.matcher(sql);
        if (matcher.find()) {
            SqlParseResult result = new SqlParseResult();
            result.setSqlType("UPDATE");
            
            // schema.table 형식 파싱
            String schemaName = matcher.group(1);  // schema (있을 수도 없을 수도 있음)
            String tableName = matcher.group(2);   // table
            
            result.setSchemaName(schemaName);  // schema가 없으면 null
            result.setTableName(tableName);
            
            // SET 절의 컬럼 목록 추출
            String setClause = matcher.group(3).trim();
            // 콤마로 분리 (단, 괄호 안의 콤마는 제외)
            java.util.List<String> assignments = new java.util.ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int i = 0; i < setClause.length(); i++) {
                char c = setClause.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 0) {
                    assignments.add(setClause.substring(start, i).trim());
                    start = i + 1;
                }
            }
            if (start < setClause.length()) {
                assignments.add(setClause.substring(start).trim());
            }
            
            String[] columns = new String[assignments.size()];
            for (int i = 0; i < assignments.size(); i++) {
                String assignment = assignments.get(i);
                // col = ? 또는 col=? 형식에서 컬럼명 추출
                int equalsIndex = assignment.indexOf('=');
                if (equalsIndex > 0) {
                    String columnName = assignment.substring(0, equalsIndex).trim();
                    // 테이블 별칭 제거 (table.col -> col)
                    int dotIndex = columnName.lastIndexOf('.');
                    if (dotIndex > 0) {
                        columnName = columnName.substring(dotIndex + 1);
                    }
                    columns[i] = columnName;
                } else {
                    columns[i] = null;
                }
            }
            result.setColumns(columns);
            
            return result;
        }
        return null;
    }
    
/**
 * SELECT 문 파싱
 * 
 * Hibernate alias 패턴 지원:
 * - user0_.email as email3_0_ → alias 매핑: email3_0_ → email
 */
/**
 * SELECT 문 파싱
 * 
 * SELECT ... FROM schema.table [alias] 또는 SELECT ... FROM table [alias] 형식 지원
 */
private SqlParseResult parseSelect(String sql) {
    Matcher matcher = SELECT_PATTERN.matcher(sql);
    if (matcher.find()) {
        SqlParseResult result = new SqlParseResult();
        result.setSqlType("SELECT");
        
        // FROM 절에서 schema.table 형식 파싱
        String schemaName = matcher.group(2);  // schema (있을 수도 없을 수도 있음)
        String tableName = matcher.group(3);   // table
        
        result.setSchemaName(schemaName);  // schema가 없으면 null
        result.setTableName(tableName);
        
        // SELECT 절의 컬럼 목록 추출
        String selectClause = matcher.group(1);
        java.util.List<String> columnList = new java.util.ArrayList<>();
        
        if (selectClause.trim().equals("*")) {
            // * 인 경우는 나중에 ResultSetMetaData로 확인
        } else {
            String[] rawColumns = selectClause.split(",");
            for (String rawCol : rawColumns) {
                String col = rawCol.trim();
                String originalColumnName = null;
                String aliasName = null;
                
                // 별칭 처리 (AS alias) - 대소문자 구분 없이 처리
                int asIndex = col.toUpperCase().lastIndexOf(" AS ");
                if (asIndex > 0) {
                    // "user0_.email as email3_0_" → aliasName = "email3_0_"
                    aliasName = col.substring(asIndex + 4).trim();
                    col = col.substring(0, asIndex).trim();
                }
                
                // table.col 또는 col 형식에서 원본 컬럼명 추출
                int dotIndex = col.lastIndexOf('.');
                if (dotIndex > 0) {
                    // "user0_.email" → originalColumnName = "email"
                    originalColumnName = col.substring(dotIndex + 1).trim();
                } else {
                    originalColumnName = col;
                }
                
                // alias 매핑 추가 (Hibernate 지원)
                if (aliasName != null && originalColumnName != null) {
                    result.addAliasMapping(aliasName, originalColumnName);
                    log.trace("Alias mapping added: {} -> {}", aliasName, originalColumnName);
                }
                
                // 원본 컬럼명 저장
                columnList.add(originalColumnName);
            }
        }
        
        result.setColumns(columnList.toArray(new String[0]));
        
        if (result.hasAliasMapping()) {
            log.trace("SELECT parsed: table={}, aliasMapping=true ({} entries)",
                     tableName, columnList.size());
        }
        
        return result;
    }
    return null;
}
}

