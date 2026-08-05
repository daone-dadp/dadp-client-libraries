package com.dadp.jdbc.policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final int MAX_SELECT_LINEAGE_DEPTH = 16;
    
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
    private Map<String, String> aliasToColumnMap = new HashMap<>();
    private Map<Integer, SourceColumn> sourceColumnByIndex = new HashMap<>();
    
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

    public void addSourceColumn(int columnIndex, SourceColumn sourceColumn) {
        if (columnIndex > 0 && sourceColumn != null && sourceColumn.isResolved()) {
            sourceColumnByIndex.put(columnIndex, sourceColumn);
        }
    }

    public SourceColumn getSourceColumn(int columnIndex) {
        return sourceColumnByIndex.get(columnIndex);
    }
}

public static class SourceColumn {
    private final String schemaName;
    private final String tableName;
    private final String columnName;

    SourceColumn(String schemaName, String tableName, String columnName) {
        this.schemaName = trimToNull(schemaName);
        this.tableName = trimToNull(tableName);
        this.columnName = trimToNull(columnName);
    }

    public String getSchemaName() {
        return schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    public boolean isResolved() {
        return tableName != null && columnName != null;
    }
}

private static class TableSource {
    final String schemaName;
    final String tableName;

    TableSource(String schemaName, String tableName) {
        this.schemaName = trimToNull(schemaName);
        this.tableName = trimToNull(tableName);
    }
}

private static class FromContext {
    final Map<String, TableSource> aliases = new HashMap<>();
    TableSource defaultSource;

    void add(String alias, TableSource source) {
        if (source == null || source.tableName == null) {
            return;
        }
        if (defaultSource == null) {
            defaultSource = source;
        }
        aliases.put(source.tableName.toLowerCase(Locale.ROOT), source);
        if (alias != null) {
            aliases.put(alias.toLowerCase(Locale.ROOT), source);
        }
    }

    TableSource resolve(String alias) {
        if (alias == null) {
            return defaultSource;
        }
        return aliases.get(alias.toLowerCase(Locale.ROOT));
    }
}

private static class Projection {
    final String expression;
    final String alias;

    Projection(String expression, String alias) {
        this.expression = expression;
        this.alias = alias;
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
    SqlParseResult parsed = parseSelectWithLineage(sql, 0);
    if (parsed != null) {
        return parsed;
    }

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

private SqlParseResult parseSelectWithLineage(String sql, int depth) {
    if (depth > MAX_SELECT_LINEAGE_DEPTH || sql == null) {
        return null;
    }

    String text = stripOuterParentheses(sql.trim());
    if (!startsWithKeyword(text, "SELECT")) {
        return null;
    }

    int fromIndex = findTopLevelKeyword(text, "FROM", 6);
    if (fromIndex < 0) {
        return null;
    }

    int fromClauseStart = fromIndex + 4;
    int fromClauseEnd = findFirstTopLevelKeyword(text, fromClauseStart,
            "WHERE", "GROUP BY", "HAVING", "ORDER BY", "LIMIT", "OFFSET", "FETCH", "UNION");
    if (fromClauseEnd < 0) {
        fromClauseEnd = text.length();
    }

    String selectClause = text.substring(6, fromIndex).trim();
    String fromClause = text.substring(fromClauseStart, fromClauseEnd).trim();
    FromContext fromContext = parseFromContext(fromClause, depth);

    SqlParseResult result = new SqlParseResult();
    result.setSqlType("SELECT");
    if (fromContext.defaultSource != null) {
        result.setSchemaName(fromContext.defaultSource.schemaName);
        result.setTableName(fromContext.defaultSource.tableName);
    }

    List<String> selectItems = splitTopLevel(selectClause, ',');
    List<String> columns = new ArrayList<>();
    for (int i = 0; i < selectItems.size(); i++) {
        Projection projection = splitProjectionAlias(selectItems.get(i));
        SourceColumn sourceColumn = resolveProjectionSource(projection.expression, fromContext, depth + 1);
        String columnName = sourceColumn != null ? sourceColumn.getColumnName() : legacyColumnName(projection.expression);
        columns.add(columnName);
        if (projection.alias != null && columnName != null) {
            result.addAliasMapping(projection.alias, columnName);
        }
        if (sourceColumn != null && sourceColumn.isResolved()) {
            result.addSourceColumn(i + 1, sourceColumn);
        }
    }
    result.setColumns(columns.toArray(new String[0]));
    return result;
}

private FromContext parseFromContext(String fromClause, int depth) {
    FromContext context = new FromContext();
    int index = 0;
    while (index < fromClause.length()) {
        index = skipWhitespace(fromClause, index);
        if (startsWithKeywordAt(fromClause, index, "INNER")
                || startsWithKeywordAt(fromClause, index, "LEFT")
                || startsWithKeywordAt(fromClause, index, "RIGHT")
                || startsWithKeywordAt(fromClause, index, "FULL")
                || startsWithKeywordAt(fromClause, index, "OUTER")
                || startsWithKeywordAt(fromClause, index, "CROSS")) {
            index = skipJoinPrefix(fromClause, index);
        }
        if (startsWithKeywordAt(fromClause, index, "JOIN")) {
            index += 4;
            index = skipWhitespace(fromClause, index);
        }
        if (startsWithKeywordAt(fromClause, index, "ON")) {
            int nextJoin = findNextJoinBoundary(fromClause, index + 2);
            if (nextJoin < 0) {
                break;
            }
            index = nextJoin;
            continue;
        }

        ParsedTableRef tableRef = parseTableRef(fromClause, index, depth);
        if (tableRef == null) {
            int nextJoin = findNextJoinBoundary(fromClause, index + 1);
            if (nextJoin < 0) {
                break;
            }
            index = nextJoin;
            continue;
        }
        context.add(tableRef.alias, tableRef.source);
        index = tableRef.nextIndex;

        int nextJoin = findNextJoinBoundary(fromClause, index);
        if (nextJoin < 0) {
            break;
        }
        index = nextJoin;
    }
    return context;
}

private static class ParsedTableRef {
    final TableSource source;
    final String alias;
    final int nextIndex;

    ParsedTableRef(TableSource source, String alias, int nextIndex) {
        this.source = source;
        this.alias = alias;
        this.nextIndex = nextIndex;
    }
}

private ParsedTableRef parseTableRef(String fromClause, int start, int depth) {
    int index = skipWhitespace(fromClause, start);
    if (index >= fromClause.length()) {
        return null;
    }

    TableSource source;
    if (fromClause.charAt(index) == '(') {
        int close = findMatchingParen(fromClause, index);
        if (close < 0) {
            return null;
        }
        String inner = fromClause.substring(index + 1, close);
        source = resolveDerivedTableSource(inner, depth + 1);
        index = close + 1;
    } else {
        String tableToken = readIdentifierPath(fromClause, index);
        if (tableToken == null) {
            return null;
        }
        source = tableSourceFromToken(tableToken);
        index += tableToken.length();
    }

    index = skipWhitespace(fromClause, index);
    if (startsWithKeywordAt(fromClause, index, "AS")) {
        index += 2;
        index = skipWhitespace(fromClause, index);
    }

    String alias = null;
    String aliasToken = readIdentifier(fromClause, index);
    if (aliasToken != null && !isJoinBoundaryKeyword(aliasToken)) {
        alias = cleanIdentifier(aliasToken);
        index += aliasToken.length();
    }
    return new ParsedTableRef(source, alias, index);
}

private TableSource resolveDerivedTableSource(String innerSql, int depth) {
    SqlParseResult inner = parseSelectWithLineage(innerSql, depth);
    if (inner == null || inner.getTableName() == null) {
        return null;
    }
    return new TableSource(inner.getSchemaName(), inner.getTableName());
}

private SourceColumn resolveProjectionSource(String expression, FromContext fromContext, int depth) {
    if (depth > MAX_SELECT_LINEAGE_DEPTH || expression == null) {
        return null;
    }
    String expr = stripOuterParentheses(expression.trim());
    if (startsWithKeyword(expr, "SELECT")) {
        SqlParseResult inner = parseSelectWithLineage(expr, depth);
        if (inner == null || inner.getColumns() == null || inner.getColumns().length != 1) {
            return null;
        }
        return inner.getSourceColumn(1);
    }
    if (!isSimpleColumnReference(expr)) {
        return null;
    }

    String qualifier = null;
    String columnName = expr;
    int dotIndex = expr.lastIndexOf('.');
    if (dotIndex > 0) {
        qualifier = cleanIdentifier(expr.substring(0, dotIndex));
        columnName = expr.substring(dotIndex + 1);
    }
    columnName = cleanIdentifier(columnName);
    TableSource source = fromContext.resolve(qualifier);
    if (source == null) {
        return null;
    }
    return new SourceColumn(source.schemaName, source.tableName, columnName);
}

private Projection splitProjectionAlias(String selectItem) {
    String item = selectItem.trim();
    int asIndex = findLastTopLevelKeyword(item, "AS");
    if (asIndex >= 0) {
        String alias = cleanIdentifier(item.substring(asIndex + 2).trim());
        return new Projection(item.substring(0, asIndex).trim(), alias);
    }
    return new Projection(item, null);
}

private String legacyColumnName(String expression) {
    String expr = stripOuterParentheses(expression.trim());
    int dotIndex = expr.lastIndexOf('.');
    if (dotIndex > 0) {
        return cleanIdentifier(expr.substring(dotIndex + 1));
    }
    return cleanIdentifier(expr);
}

private TableSource tableSourceFromToken(String token) {
    String cleaned = cleanIdentifier(token);
    int dotIndex = cleaned.lastIndexOf('.');
    if (dotIndex > 0) {
        return new TableSource(cleaned.substring(0, dotIndex), cleaned.substring(dotIndex + 1));
    }
    return new TableSource(null, cleaned);
}

private List<String> splitTopLevel(String value, char delimiter) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    char quote = 0;
    for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (quote != 0) {
            if (c == quote) {
                quote = 0;
            }
            continue;
        }
        if (c == '\'' || c == '"' || c == '`') {
            quote = c;
        } else if (c == '(') {
            depth++;
        } else if (c == ')') {
            depth = Math.max(0, depth - 1);
        } else if (c == delimiter && depth == 0) {
            parts.add(value.substring(start, i).trim());
            start = i + 1;
        }
    }
    parts.add(value.substring(start).trim());
    return parts;
}

private int findTopLevelKeyword(String value, String keyword, int fromIndex) {
    int depth = 0;
    char quote = 0;
    for (int i = Math.max(0, fromIndex); i <= value.length() - keyword.length(); i++) {
        char c = value.charAt(i);
        if (quote != 0) {
            if (c == quote) {
                quote = 0;
            }
            continue;
        }
        if (c == '\'' || c == '"' || c == '`') {
            quote = c;
        } else if (c == '(') {
            depth++;
        } else if (c == ')') {
            depth = Math.max(0, depth - 1);
        } else if (depth == 0 && startsWithKeywordAt(value, i, keyword)) {
            return i;
        }
    }
    return -1;
}

private int findFirstTopLevelKeyword(String value, int fromIndex, String... keywords) {
    int found = -1;
    for (String keyword : keywords) {
        int index = findTopLevelKeyword(value, keyword, fromIndex);
        if (index >= 0 && (found < 0 || index < found)) {
            found = index;
        }
    }
    return found;
}

private int findLastTopLevelKeyword(String value, String keyword) {
    int found = -1;
    int index = 0;
    while (index >= 0 && index < value.length()) {
        int next = findTopLevelKeyword(value, keyword, index);
        if (next < 0) {
            break;
        }
        found = next;
        index = next + keyword.length();
    }
    return found;
}

private int findNextJoinBoundary(String value, int fromIndex) {
    return findFirstTopLevelKeyword(value, fromIndex, "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", ",");
}

private int skipJoinPrefix(String value, int index) {
    while (index < value.length()) {
        index = skipWhitespace(value, index);
        String token = readIdentifier(value, index);
        if (token == null) {
            return index;
        }
        String upper = token.toUpperCase(Locale.ROOT);
        if ("JOIN".equals(upper)) {
            return index + token.length();
        }
        if (!"INNER".equals(upper) && !"LEFT".equals(upper) && !"RIGHT".equals(upper)
                && !"FULL".equals(upper) && !"OUTER".equals(upper) && !"CROSS".equals(upper)) {
            return index;
        }
        index += token.length();
    }
    return index;
}

private int findMatchingParen(String value, int openIndex) {
    int depth = 0;
    char quote = 0;
    for (int i = openIndex; i < value.length(); i++) {
        char c = value.charAt(i);
        if (quote != 0) {
            if (c == quote) {
                quote = 0;
            }
            continue;
        }
        if (c == '\'' || c == '"' || c == '`') {
            quote = c;
        } else if (c == '(') {
            depth++;
        } else if (c == ')') {
            depth--;
            if (depth == 0) {
                return i;
            }
        }
    }
    return -1;
}

private String stripOuterParentheses(String value) {
    String text = value.trim();
    while (text.startsWith("(") && text.endsWith(")")) {
        int close = findMatchingParen(text, 0);
        if (close != text.length() - 1) {
            break;
        }
        text = text.substring(1, text.length() - 1).trim();
    }
    return text;
}

private boolean startsWithKeyword(String value, String keyword) {
    return startsWithKeywordAt(value, 0, keyword);
}

private boolean startsWithKeywordAt(String value, int index, String keyword) {
    if (index < 0 || index + keyword.length() > value.length()) {
        return false;
    }
    if (!value.regionMatches(true, index, keyword, 0, keyword.length())) {
        return false;
    }
    boolean beforeOk = index == 0 || !isIdentifierPart(value.charAt(index - 1));
    int after = index + keyword.length();
    boolean afterOk = after >= value.length() || !isIdentifierPart(value.charAt(after));
    return beforeOk && afterOk;
}

private int skipWhitespace(String value, int index) {
    while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
        index++;
    }
    if (index < value.length() && value.charAt(index) == ',') {
        return skipWhitespace(value, index + 1);
    }
    return index;
}

private String readIdentifierPath(String value, int index) {
    int start = index;
    while (index < value.length()) {
        char c = value.charAt(index);
        if (isIdentifierPart(c) || c == '.' || c == '"' || c == '`' || c == '[' || c == ']') {
            index++;
        } else {
            break;
        }
    }
    return index > start ? value.substring(start, index) : null;
}

private String readIdentifier(String value, int index) {
    int start = index;
    while (index < value.length() && isIdentifierPart(value.charAt(index))) {
        index++;
    }
    return index > start ? value.substring(start, index) : null;
}

private boolean isSimpleColumnReference(String value) {
    return value.matches("[`\"\\[]?[A-Za-z_][A-Za-z0-9_$]*[`\"\\]]?(\\.[`\"\\[]?[A-Za-z_][A-Za-z0-9_$]*[`\"\\]]?){0,2}");
}

private boolean isJoinBoundaryKeyword(String token) {
    String upper = token.toUpperCase(Locale.ROOT);
    return "ON".equals(upper) || "INNER".equals(upper) || "LEFT".equals(upper)
            || "RIGHT".equals(upper) || "FULL".equals(upper) || "OUTER".equals(upper)
            || "CROSS".equals(upper) || "JOIN".equals(upper) || "WHERE".equals(upper);
}

private boolean isIdentifierPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
}

private static String cleanIdentifier(String value) {
    String text = trimToNull(value);
    if (text == null) {
        return null;
    }
    if ((text.startsWith("\"") && text.endsWith("\""))
            || (text.startsWith("`") && text.endsWith("`"))
            || (text.startsWith("[") && text.endsWith("]"))) {
        return text.substring(1, text.length() - 1);
    }
    return text;
}

private static String trimToNull(String value) {
    if (value == null) {
        return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
}
}
