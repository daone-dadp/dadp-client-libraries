package com.dadp.jdbc;

import com.dadp.common.sync.crypto.DirectCryptoAdapter;
import com.dadp.common.sync.policy.PolicyResolver;
import com.dadp.jdbc.policy.SqlParser;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

/**
 * DADP Proxy PreparedStatement
 * 
 * PreparedStatement를 래핑하여 파라미터 바인딩 시 암호화 처리를 수행합니다.
 * 
 * @author DADP Development Team
 * @version 4.8.0
 * @since 2025-11-07
 */
public class DadpProxyPreparedStatement implements PreparedStatement {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(DadpProxyPreparedStatement.class);
    
    private final PreparedStatement actualPreparedStatement;
    private final String sql;
    private final DadpProxyConnection proxyConnection;
    private final SqlParser.SqlParseResult sqlParseResult;
    private final Map<Integer, String> parameterToColumnMap; // parameterIndex -> columnName
    private final Set<Integer> whereClauseParamIndices; // UPDATE WHERE clause parameter indices (for search encryption routing)
    private final Map<Integer, String> originalDataMap; // parameterIndex -> original plaintext data (for fail-open on truncation)
    
    public DadpProxyPreparedStatement(PreparedStatement actualPs, String sql, DadpProxyConnection proxyConnection) {
        this.actualPreparedStatement = actualPs;
        this.sql = sql;
        this.proxyConnection = proxyConnection;
        
        // SQL 파싱
        SqlParser sqlParser = new SqlParser();
        this.sqlParseResult = sqlParser.parse(sql);
        
        // 파라미터 인덱스와 컬럼명 매핑 생성
        this.whereClauseParamIndices = new HashSet<>();
        this.parameterToColumnMap = buildParameterMapping(sqlParseResult);
        
        // 원본 데이터 저장용 맵 초기화 (Data truncation 시 평문으로 재시도)
        this.originalDataMap = new HashMap<>();
        
        // INSERT/UPDATE 쿼리인 경우 상세 로그 출력 (디버깅용)
        if (sqlParseResult != null && ("INSERT".equals(sqlParseResult.getSqlType()) || "UPDATE".equals(sqlParseResult.getSqlType()))) {
            log.trace("INSERT/UPDATE PreparedStatement created: sql={}, table={}, columns={}, parameterMapping={}",
                    sql, sqlParseResult.getTableName(), 
                    sqlParseResult.getColumns() != null ? String.join(", ", sqlParseResult.getColumns()) : "null",
                    parameterToColumnMap);
        } else if (sqlParseResult != null && !parameterToColumnMap.isEmpty()) {
            log.trace("DADP Proxy PreparedStatement created: {} ({} parameter mappings)", sql, parameterToColumnMap.size());
        } else {
            log.trace("DADP Proxy PreparedStatement created: {}", sql);
        }
    }
    
    /**
     * SQL 파싱 결과로부터 파라미터 인덱스와 컬럼명 매핑 생성
     * INSERT/UPDATE: SET 절의 컬럼만 매핑
     * SELECT: WHERE 절의 파라미터도 매핑
     */
    private Map<Integer, String> buildParameterMapping(SqlParser.SqlParseResult parseResult) {
        Map<Integer, String> mapping = new HashMap<>();
        
        if (parseResult == null) {
            return mapping;
        }
        
        // INSERT/UPDATE: SET 절 또는 VALUES 절의 컬럼 매핑
        if ("INSERT".equals(parseResult.getSqlType()) || "UPDATE".equals(parseResult.getSqlType())) {
            if (parseResult.getColumns() != null) {
                String[] columns = parseResult.getColumns();
                for (int i = 0; i < columns.length; i++) {
                    // null이 아닌 컬럼명만 매핑
                    if (columns[i] != null && !columns[i].trim().isEmpty()) {
                        // 파라미터 인덱스는 1부터 시작
                        mapping.put(i + 1, columns[i].trim());
                    }
                }
            }
            // UPDATE: WHERE 절 파라미터도 매핑 (ECB 검색용 암호화 지원)
            if ("UPDATE".equals(parseResult.getSqlType())) {
                Set<Integer> beforeKeys = new HashSet<>(mapping.keySet());
                parseWhereClauseParameters(sql, parseResult.getTableName(), mapping);
                for (Integer key : mapping.keySet()) {
                    if (!beforeKeys.contains(key)) {
                        whereClauseParamIndices.add(key);
                    }
                }
            }
        }
        // SELECT: WHERE 절의 파라미터 매핑
        else if ("SELECT".equals(parseResult.getSqlType())) {
            // WHERE 절에서 파라미터와 컬럼 매핑 추출
            parseWhereClauseParameters(sql, parseResult.getTableName(), mapping);
        }
        
        return mapping;
    }
    
    /**
     * WHERE 절에서 파라미터와 컬럼명 매핑 추출
     * 예: WHERE u1_0.phone like ? -> parameterIndex 1 -> phone
     */
    private void parseWhereClauseParameters(String sql, String tableName, Map<Integer, String> mapping) {
        if (sql == null || tableName == null) {
            return;
        }
        
        // WHERE 절 찾기
        int whereIndex = sql.toUpperCase().indexOf(" WHERE ");
        if (whereIndex < 0) {
            return;
        }
        
        // WHERE 절 이전의 ? 개수 계산 (INSERT/UPDATE의 VALUES/SET 절 파라미터)
        String beforeWhere = sql.substring(0, whereIndex);
        int beforeWhereParamCount = countParameters(beforeWhere);
        
        String whereClause = sql.substring(whereIndex + 7); // " WHERE " 길이
        
        // WHERE 절에서 파라미터 위치와 컬럼명 매핑
        // 패턴: table.col like ?, table.col = ?, table.col > ? 등
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "(?:\\w+\\.)?(\\w+)\\s*(?:like|=|!=|<>|>|<|>=|<=|in|not\\s+in)\\s*\\?",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(whereClause);
        
        while (matcher.find()) {
            String columnName = matcher.group(1);
            // WHERE 절의 ? 위치 찾기
            int questionMarkIndex = matcher.end() - 1; // ? 위치
            // WHERE 절 내에서 이 ? 이전의 ? 개수 계산
            String beforeQuestionMark = whereClause.substring(0, questionMarkIndex);
            int localParamIndex = countParameters(beforeQuestionMark);
            // 전체 파라미터 인덱스 = WHERE 절 이전 파라미터 개수 + WHERE 절 내 파라미터 인덱스
            int globalParamIndex = beforeWhereParamCount + localParamIndex + 1; // 1-based
            
            if (!mapping.containsKey(globalParamIndex)) {
                mapping.put(globalParamIndex, columnName);
                log.trace("WHERE clause parameter mapping: parameterIndex={} -> column={}", globalParamIndex, columnName);
            }
        }
    }
    
    /**
     * SQL 문자열에서 ? 파라미터 개수 계산
     */
    private int countParameters(String sql) {
        if (sql == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }
    
    @Override
    public ResultSet executeQuery() throws SQLException {
        long start = System.nanoTime();
        boolean error = false;
        try {
            ResultSet actualRs = actualPreparedStatement.executeQuery();
            return new DadpProxyResultSet(actualRs, sql, proxyConnection);
        } catch (SQLException e) {
            error = true;
            throw e;
        } finally {
            recordTelemetry(start, error);
        }
    }
    
    @Override
    public int executeUpdate() throws SQLException {
        long start = System.nanoTime();
        boolean error = false;
        try {
            return actualPreparedStatement.executeUpdate();
        } catch (SQLException e) {
            error = true;
            // Data truncation 에러 감지 (암호화된 데이터가 컬럼 크기 초과)
            if (e.getErrorCode() == 1406 || 
                (e.getMessage() != null && e.getMessage().contains("Data too long"))) {
                
                // 원본 데이터가 저장된 파라미터가 있는지 확인
                if (originalDataMap.isEmpty()) {
                    // 원본 데이터가 없으면 원래 예외 발생
                    log.warn("Data truncation error but no original data available for retry: {}", e.getMessage());
                    throw e;
                }
                
                String tableName = sqlParseResult != null ? sqlParseResult.getTableName() : null;
                
                // 모든 암호화된 파라미터를 원본 데이터로 되돌리기
                int restoredCount = 0;
                for (Map.Entry<Integer, String> entry : originalDataMap.entrySet()) {
                    Integer paramIndex = entry.getKey();
                    String originalData = entry.getValue();
                    
                    // 원본 데이터로 재설정
                    actualPreparedStatement.setString(paramIndex, originalData);
                    restoredCount++;
                    
                    // 해당 파라미터의 컬럼명 찾기
                    String paramColumnName = parameterToColumnMap.get(paramIndex);
                    if (paramColumnName != null) {
                        // datasourceId와 schemaName 결정
                        String datasourceId = proxyConnection.getDatasourceId();
                        String schemaName = sqlParseResult != null ? sqlParseResult.getSchemaName() : null;
                        if (schemaName == null || schemaName.trim().isEmpty()) {
                            schemaName = proxyConnection.getCurrentSchemaName();
                            if (schemaName == null || schemaName.trim().isEmpty()) {
                                schemaName = proxyConnection.getCurrentDatabaseName();
                            }
                        }
                        // 식별자 정규화 (스키마 로드 시와 동일한 방식으로 정규화)
                        String normalizedSchemaName = proxyConnection.normalizeIdentifier(schemaName);
                        String normalizedTableName = proxyConnection.normalizeIdentifier(tableName);
                        String normalizedParamColumnName = proxyConnection.normalizeIdentifier(paramColumnName);
                        String policyName = proxyConnection.getPolicyResolver().resolvePolicy(datasourceId, normalizedSchemaName, normalizedTableName, normalizedParamColumnName);
                        String errorMsg = "Encrypted data exceeds column size (original: " +
                                         (originalData != null ? originalData.length() : 0) + " chars)";
                        log.warn("Encrypted data exceeds column size: {}.{} (policy: {}), retrying with plaintext - {}",
                                 tableName, paramColumnName, policyName, errorMsg);
                        
                        // 암복호화 실패 알림은 엔진에서 전송하므로 Wrapper에서는 제거
                    } else {
                        log.warn("Encrypted data exceeds column size: parameterIndex={}, retrying with plaintext", paramIndex);
                    }
                }
                
                log.warn("Data truncation occurred: {} parameters reverted to plaintext for retry", restoredCount);

                // 평문으로 재시도
                try {
                    return actualPreparedStatement.executeUpdate();
                } catch (SQLException retryException) {
                    // 재시도에서도 실패하면 원래 예외 발생
                    log.error("Retry with plaintext still failed: {}", retryException.getMessage());
                    throw e; // 원래 예외 발생
                }
            } else {
                // 다른 SQLException은 그대로 발생
                throw e;
            }
        } finally {
            recordTelemetry(start, error);
        }
    }
    
    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        actualPreparedStatement.setNull(parameterIndex, sqlType);
    }
    
    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        actualPreparedStatement.setBoolean(parameterIndex, x);
    }
    
    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        actualPreparedStatement.setByte(parameterIndex, x);
    }
    
    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        actualPreparedStatement.setShort(parameterIndex, x);
    }
    
    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        actualPreparedStatement.setInt(parameterIndex, x);
    }
    
    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        actualPreparedStatement.setLong(parameterIndex, x);
    }
    
    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        actualPreparedStatement.setFloat(parameterIndex, x);
    }
    
    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        actualPreparedStatement.setDouble(parameterIndex, x);
    }
    
    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        actualPreparedStatement.setBigDecimal(parameterIndex, x);
    }
    
    /**
     * String 파라미터 암호화 처리 결과
     */
    private static class EncryptionResult {
        final String value;  // 암호화된 값 또는 원본 값
        final boolean shouldSkip;  // SELECT WHERE 절 등으로 인해 암호화를 건너뛰어야 하는지
        
        EncryptionResult(String value, boolean shouldSkip) {
            this.value = value;
            this.shouldSkip = shouldSkip;
        }
    }
    
    /**
     * String 파라미터에 대한 암호화 처리 공통 로직
     * 
     * @param parameterIndex 파라미터 인덱스
     * @param value 원본 값
     * @param methodName 메서드 이름 (로깅용: "setString", "setNString", "setObject")
     * @return EncryptionResult 암호화 처리 결과
     */
    private EncryptionResult processStringEncryption(int parameterIndex, String value, String methodName) {
        // null/빈 문자열 체크 및 SQL 파싱 결과 확인
        if (value == null || value.isEmpty() || sqlParseResult == null) {
            if (value != null && sqlParseResult == null) {
                log.debug("{}: No SQL parse result: cannot determine encryption target, parameterIndex={}", methodName, parameterIndex);
            }
            return new EncryptionResult(value, false);
        }
        
        String columnName = parameterToColumnMap.get(parameterIndex);
        String tableName = sqlParseResult.getTableName();
        
        // INSERT/UPDATE 쿼리인 경우 상세 로그 출력 (디버깅용)
        if ("INSERT".equals(sqlParseResult.getSqlType()) || "UPDATE".equals(sqlParseResult.getSqlType())) {
            log.trace("{} called: parameterIndex={}, columnName={}, tableName={}, valueLength={}, sqlType={}",
                    methodName, parameterIndex, columnName, tableName, value != null ? value.length() : 0, sqlParseResult.getSqlType());
        }
        
        if (columnName == null || tableName == null) {
            log.debug("{}: Missing table or column name: cannot determine encryption target, tableName={}, columnName={}, parameterIndex={}",
                    methodName, tableName, columnName, parameterIndex);
            return new EncryptionResult(value, false);
        }
        
        // SELECT WHERE / UPDATE WHERE 절: 로컬 캐시로 검색용 암호화 필요 여부 판단
        // PolicyResolver가 useIv/usePlain을 캐싱하므로 Engine 호출 없이 판단 가능.
        // - useIv=false AND usePlain=false → Engine 호출하여 고정 IV 전체 암호화
        // - 그 외 → 평문 반환 (Engine 호출 불필요)
        boolean isSearchContext = "SELECT".equals(sqlParseResult.getSqlType()) ||
                ("UPDATE".equals(sqlParseResult.getSqlType()) && whereClauseParamIndices.contains(parameterIndex));
        if (isSearchContext) {
            String datasourceId = proxyConnection.getDatasourceId();
            String schemaName = sqlParseResult.getSchemaName();
            if (schemaName == null || schemaName.trim().isEmpty()) {
                schemaName = proxyConnection.getCurrentSchemaName();
                if (schemaName == null || schemaName.trim().isEmpty()) {
                    schemaName = proxyConnection.getCurrentDatabaseName();
                }
            }
            String nSchema = proxyConnection.normalizeIdentifier(schemaName);
            String nTable = proxyConnection.normalizeIdentifier(tableName);
            String nColumn = proxyConnection.normalizeIdentifier(columnName);

            PolicyResolver policyResolver = proxyConnection.getPolicyResolver();
            String policyName = policyResolver.resolvePolicy(datasourceId, nSchema, nTable, nColumn);
            if (policyName == null) {
                log.trace("{}: SELECT WHERE clause: not an encryption target, {}.{}", methodName, tableName, columnName);
                return new EncryptionResult(value, true);
            }

            // 로컬 캐시로 검색 암호화 필요 여부 판단 (Engine 호출 없이)
            if (!policyResolver.isSearchEncryptionNeeded(policyName)) {
                log.trace("{}: SELECT WHERE clause: search encryption not needed (useIv=true or usePlain=true), {}.{} (policy: {})",
                        methodName, tableName, columnName, policyName);
                return new EncryptionResult(value, true);
            }

            // 와일드카드(%, _) 포함 시 평문 검색 (부분암호화 컬럼의 평문 부분으로 검색 가능)
            if (value.contains("%") || value.contains("_")) {
                log.trace("{}: SELECT WHERE clause: wildcard detected, plaintext search, {}.{} (policy: {})",
                        methodName, tableName, columnName, policyName);
                return new EncryptionResult(value, true);
            }

            // 와일드카드 없음 → 전체 암호화 후 검색 (Engine 호출)
            DirectCryptoAdapter adapter = proxyConnection.getDirectCryptoAdapter();
            if (adapter == null || !adapter.isEndpointAvailable()) {
                log.trace("{}: SELECT WHERE clause: adapter not available, plaintext search, {}.{}", methodName, tableName, columnName);
                return new EncryptionResult(value, true);
            }

            try {
                String searchValue = adapter.encryptForSearch(value, policyName);
                boolean encrypted = !value.equals(searchValue);
                if (encrypted) {
                    log.trace("{} search encryption: {}.{} (policy: {})", methodName, tableName, columnName, policyName);
                } else {
                    log.trace("{} search plaintext: {}.{} (policy: {})", methodName, tableName, columnName, policyName);
                }
                return new EncryptionResult(searchValue, false);
            } catch (Exception e) {
                log.warn("{} search encryption failed, using plaintext: {}.{} - {}", methodName, tableName, columnName, e.getMessage());
                return new EncryptionResult(value, true);
            }
        }

        // datasourceId와 schemaName 결정
        String datasourceId = proxyConnection.getDatasourceId();
        String schemaName = sqlParseResult.getSchemaName();
        if (schemaName == null || schemaName.trim().isEmpty()) {
            // SQL 파싱 결과에 스키마 이름이 없으면 Connection에서 가져옴
            // PostgreSQL의 경우 스키마 이름(public), MySQL의 경우 데이터베이스 이름
            schemaName = proxyConnection.getCurrentSchemaName();
            if (schemaName == null || schemaName.trim().isEmpty()) {
                schemaName = proxyConnection.getCurrentDatabaseName();
            }
        }
        
        // 식별자 정규화 (스키마 로드 시와 동일한 방식으로 정규화)
        String normalizedSchemaName = proxyConnection.normalizeIdentifier(schemaName);
        String normalizedTableName = proxyConnection.normalizeIdentifier(tableName);
        String normalizedColumnName = proxyConnection.normalizeIdentifier(columnName);
        
        // INSERT/UPDATE 쿼리인 경우 datasourceId와 schemaName 로그 출력 (디버깅용)
        if ("INSERT".equals(sqlParseResult.getSqlType()) || "UPDATE".equals(sqlParseResult.getSqlType())) {
            log.trace("{}: Policy lookup params: datasourceId={}, schemaName={}->{}, tableName={}->{}, columnName={}->{}",
                    methodName, datasourceId, schemaName, normalizedSchemaName, 
                    tableName, normalizedTableName, columnName, normalizedColumnName);
        }
        
        // PolicyResolver에서 정책 확인 (메모리 캐시에서 조회)
        PolicyResolver policyResolver = proxyConnection.getPolicyResolver();
        String policyName = policyResolver.resolvePolicy(datasourceId, normalizedSchemaName, normalizedTableName, normalizedColumnName);
        
        // INSERT/UPDATE 쿼리인 경우 정책 확인 결과 로그 출력 (디버깅용)
        if ("INSERT".equals(sqlParseResult.getSqlType()) || "UPDATE".equals(sqlParseResult.getSqlType())) {
            log.trace("{}: Policy check: {}.{} -> policyName={}", methodName, tableName, columnName, policyName);
        }
        
        if (policyName == null) {
            log.trace("{}: Not an encryption target: {}.{}", methodName, tableName, columnName);
            return new EncryptionResult(value, false);
        }
        
        // 암호화 대상: 직접 암복호화 어댑터 사용 (Engine/Gateway 직접 연결)
        DirectCryptoAdapter adapter = proxyConnection.getDirectCryptoAdapter();
        if (adapter == null) {
            log.warn("{}: Direct crypto adapter not initialized: {}.{} (policy: {})",
                    methodName, tableName, columnName, policyName);
            if (proxyConnection.getConfig().isFailOpen()) {
                return new EncryptionResult(value, false);
            } else {
                // SQLException을 RuntimeException으로 감싸서 throw (호출자가 처리)
                throw new RuntimeException(new SQLException("Crypto adapter is not initialized"));
            }
        }
        
        try {
            String encrypted = adapter.encrypt(value, policyName);
            
            // 원본 데이터 저장 (Data truncation 시 평문으로 재시도하기 위해)
            originalDataMap.put(parameterIndex, value);
            
            log.trace("{} encryption completed: {}.{} -> {} (policy: {})", methodName, tableName, columnName,
                     encrypted != null && encrypted.length() > 50 ? encrypted.substring(0, 50) + "..." : encrypted,
                     policyName);
            return new EncryptionResult(encrypted, false);
        } catch (Exception e) {
            // 암호화 실패 시 경고 레벨로 간략하게 출력하고 평문으로 저장
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("{} encryption failed: {}.{} (policy: {}), saving as plaintext - {}",
                     methodName, tableName, columnName, policyName, errorMsg);
            
            // 암복호화 실패 알림은 엔진에서 전송하므로 Wrapper에서는 제거
            
            if (proxyConnection.getConfig().isFailOpen()) {
                return new EncryptionResult(value, false);
            } else {
                // SQLException을 RuntimeException으로 감싸서 throw (호출자가 처리)
                throw new RuntimeException(new SQLException("Encryption failed: " + errorMsg, e));
            }
        }
    }
    
    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        try {
            EncryptionResult result = processStringEncryption(parameterIndex, x, "setString");
            
            if (result.shouldSkip) {
                actualPreparedStatement.setString(parameterIndex, result.value);
                return;
            }
            
            // 암호화 대상이 아니거나 암호화 실패 시 원본 데이터 그대로 저장
            actualPreparedStatement.setString(parameterIndex, result.value);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            }
            throw e;
        }
    }
    
    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        actualPreparedStatement.setBytes(parameterIndex, x);
    }
    
    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        actualPreparedStatement.setDate(parameterIndex, x);
    }
    
    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        actualPreparedStatement.setTime(parameterIndex, x);
    }
    
    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        actualPreparedStatement.setTimestamp(parameterIndex, x);
    }
    
    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        actualPreparedStatement.setAsciiStream(parameterIndex, x, length);
    }
    
    @Override
    @Deprecated
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        actualPreparedStatement.setUnicodeStream(parameterIndex, x, length);
    }
    
    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        actualPreparedStatement.setBinaryStream(parameterIndex, x, length);
    }
    
    @Override
    public void clearParameters() throws SQLException {
        actualPreparedStatement.clearParameters();
    }
    
    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        log.trace("setObject(type) called: parameterIndex={}, valueType={}, targetSqlType={}, value={}", parameterIndex,
                 x != null ? x.getClass().getSimpleName() : "null", targetSqlType,
                 x instanceof String && ((String)x).length() > 30 ? ((String)x).substring(0, 30) + "..." : x);
        
        // String 타입인 경우에만 암호화 처리 시도
        if (x instanceof String) {
            String stringValue = (String) x;
            try {
                EncryptionResult result = processStringEncryption(parameterIndex, stringValue, "setObject(type)");
                
                log.trace("setObject(type) encryption result: parameterIndex={}, shouldSkip={}, value={}", parameterIndex, result.shouldSkip,
                         result.value != null && result.value.length() > 50 ? result.value.substring(0, 50) + "..." : result.value);
                
                if (result.shouldSkip) {
                    log.trace("setObject(type) skip: parameterIndex={}, using original value", parameterIndex);
                    actualPreparedStatement.setObject(parameterIndex, result.value, targetSqlType);
                    return;
                }
                
                // VARCHAR 계열 타입이면 setString 사용 (타입 안전성)
                if (targetSqlType == Types.VARCHAR || targetSqlType == Types.NVARCHAR || 
                    targetSqlType == Types.LONGVARCHAR || targetSqlType == Types.CLOB) {
                    log.trace("setObject(type) -> delegated to setString: parameterIndex={}, encryptedValue={}", parameterIndex,
                             result.value != null && result.value.length() > 50 ? result.value.substring(0, 50) + "..." : result.value);
                    actualPreparedStatement.setString(parameterIndex, result.value);
                } else {
                    // 암호화된 경우 암호화된 값 사용, 아니면 원본 값 사용
                    log.trace("setObject(type) set: parameterIndex={}, targetSqlType={}, encryptedValue={}", parameterIndex, targetSqlType,
                             result.value != null && result.value.length() > 50 ? result.value.substring(0, 50) + "..." : result.value);
                    actualPreparedStatement.setObject(parameterIndex, result.value, targetSqlType);
                }
                return;
            } catch (RuntimeException e) {
                if (e.getCause() instanceof SQLException) {
                    throw (SQLException) e.getCause();
                }
                throw e;
            }
        }
        
        // String이 아닌 경우 원본 그대로 전달
        log.trace("setObject(type) not a String: parameterIndex={}, using original value", parameterIndex);
        actualPreparedStatement.setObject(parameterIndex, x, targetSqlType);
    }
    
    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        log.trace("setObject called: parameterIndex={}, valueType={}, value={}", parameterIndex,
                 x != null ? x.getClass().getSimpleName() : "null",
                 x instanceof String && ((String)x).length() > 30 ? ((String)x).substring(0, 30) + "..." : x);
        
        // String 타입인 경우에만 암호화 처리 시도
        if (x instanceof String) {
            String stringValue = (String) x;
            try {
                EncryptionResult result = processStringEncryption(parameterIndex, stringValue, "setObject");
                
                log.trace("setObject encryption result: parameterIndex={}, shouldSkip={}, value={}", parameterIndex, result.shouldSkip,
                         result.value != null && result.value.length() > 50 ? result.value.substring(0, 50) + "..." : result.value);
                
                if (result.shouldSkip) {
                    log.trace("setObject skip: parameterIndex={}, using original value", parameterIndex);
                    actualPreparedStatement.setObject(parameterIndex, result.value);
                    return;
                }
                
                // 암호화된 경우 암호화된 값 사용, 아니면 원본 값 사용
                // String이면 setString 사용 (타입 안전성)
                log.trace("setObject -> delegated to setString: parameterIndex={}, encryptedValue={}", parameterIndex,
                         result.value != null && result.value.length() > 50 ? result.value.substring(0, 50) + "..." : result.value);
                actualPreparedStatement.setString(parameterIndex, result.value);
                return;
            } catch (RuntimeException e) {
                if (e.getCause() instanceof SQLException) {
                    throw (SQLException) e.getCause();
                }
                throw e;
            }
        }
        
        // String이 아닌 경우 원본 그대로 전달
        log.trace("setObject not a String: parameterIndex={}, using original value", parameterIndex);
        actualPreparedStatement.setObject(parameterIndex, x);
    }
    
    @Override
    public boolean execute() throws SQLException {
        // SQL 파싱 결과를 확인하여 쿼리 타입에 따라 처리
        if (sqlParseResult != null) {
            String sqlType = sqlParseResult.getSqlType();
            
            if ("INSERT".equals(sqlType) || "UPDATE".equals(sqlType) || "DELETE".equals(sqlType)) {
                // INSERT/UPDATE/DELETE인 경우: executeUpdate()와 동일한 Data truncation 처리 로직 적용
                try {
                    boolean result = actualPreparedStatement.execute();
                    // INSERT/UPDATE/DELETE는 보통 ResultSet이 없으므로 false 반환
                    // 하지만 실제로는 execute() 결과를 그대로 반환해야 함
                    return result;
                } catch (SQLException e) {
                    // Data truncation 에러 처리 (executeUpdate()와 동일한 로직)
                    if (e.getErrorCode() == 1406 || 
                        (e.getMessage() != null && e.getMessage().contains("Data too long"))) {
                        
                        // 원본 데이터가 저장된 파라미터가 있는지 확인
                        if (originalDataMap.isEmpty()) {
                            log.warn("Data truncation error but no original data available for retry: {}", e.getMessage());
                            throw e;
                        }
                        
                        String tableName = sqlParseResult != null ? sqlParseResult.getTableName() : null;
                        
                        // 모든 암호화된 파라미터를 원본 데이터로 되돌리기
                        int restoredCount = 0;
                        for (Map.Entry<Integer, String> entry : originalDataMap.entrySet()) {
                            Integer paramIndex = entry.getKey();
                            String originalData = entry.getValue();
                            
                            // 원본 데이터로 재설정
                            actualPreparedStatement.setString(paramIndex, originalData);
                            restoredCount++;
                            
                    // 해당 파라미터의 컬럼명 찾기
                    String paramColumnName = parameterToColumnMap.get(paramIndex);
                    if (paramColumnName != null) {
                        // datasourceId와 schemaName 결정
                        String datasourceId = proxyConnection.getDatasourceId();
                        String schemaName = sqlParseResult != null ? sqlParseResult.getSchemaName() : null;
                        if (schemaName == null || schemaName.trim().isEmpty()) {
                            schemaName = proxyConnection.getCurrentSchemaName();
                            if (schemaName == null || schemaName.trim().isEmpty()) {
                                schemaName = proxyConnection.getCurrentDatabaseName();
                            }
                        }
                        // 식별자 정규화 (스키마 로드 시와 동일한 방식으로 정규화)
                        String normalizedSchemaName = proxyConnection.normalizeIdentifier(schemaName);
                        String normalizedTableName = proxyConnection.normalizeIdentifier(tableName);
                        String normalizedParamColumnName = proxyConnection.normalizeIdentifier(paramColumnName);
                        String policyName = proxyConnection.getPolicyResolver().resolvePolicy(datasourceId, normalizedSchemaName, normalizedTableName, normalizedParamColumnName);
                                String errorMsg = "Encrypted data exceeds column size (original: " +
                                                 (originalData != null ? originalData.length() : 0) + " chars)";
                                log.warn("Encrypted data exceeds column size: {}.{} (policy: {}), retrying with plaintext - {}",
                                         tableName, paramColumnName, policyName, errorMsg);
                            } else {
                                log.warn("Encrypted data exceeds column size: parameterIndex={}, retrying with plaintext", paramIndex);
                            }
                        }
                        
                        log.warn("Data truncation occurred: {} parameters reverted to plaintext for retry", restoredCount);

                        // 평문으로 재시도
                        try {
                            return actualPreparedStatement.execute();
                        } catch (SQLException retryException) {
                            // 재시도에서도 실패하면 원래 예외 발생
                            log.error("Retry with plaintext still failed: {}", retryException.getMessage());
                            throw e; // 원래 예외 발생
                        }
                    } else {
                        // 다른 SQLException은 그대로 발생
                        throw e;
                    }
                }
            }
            // SELECT나 기타 쿼리 타입은 그대로 실행 (getResultSet()에서 래핑 처리됨)
        }
        
        // SQL 파싱 결과가 없거나 알 수 없는 타입인 경우 기본 동작
        return actualPreparedStatement.execute();
    }
    
    @Override
    public void addBatch() throws SQLException {
        actualPreparedStatement.addBatch();
    }
    
    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        actualPreparedStatement.setCharacterStream(parameterIndex, reader, length);
    }
    
    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        actualPreparedStatement.setRef(parameterIndex, x);
    }
    
    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        actualPreparedStatement.setBlob(parameterIndex, x);
    }
    
    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        actualPreparedStatement.setClob(parameterIndex, x);
    }
    
    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        actualPreparedStatement.setArray(parameterIndex, x);
    }
    
    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return actualPreparedStatement.getMetaData();
    }
    
    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        actualPreparedStatement.setDate(parameterIndex, x, cal);
    }
    
    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        actualPreparedStatement.setTime(parameterIndex, x, cal);
    }
    
    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        actualPreparedStatement.setTimestamp(parameterIndex, x, cal);
    }
    
    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        actualPreparedStatement.setNull(parameterIndex, sqlType, typeName);
    }
    
    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        actualPreparedStatement.setURL(parameterIndex, x);
    }
    
    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return actualPreparedStatement.getParameterMetaData();
    }
    
    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        actualPreparedStatement.setRowId(parameterIndex, x);
    }
    
    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        try {
            EncryptionResult result = processStringEncryption(parameterIndex, value, "setNString");
            
            if (result.shouldSkip) {
                actualPreparedStatement.setNString(parameterIndex, result.value);
                return;
            }
            
            // 암호화 대상이 아니거나 암호화 실패 시 원본 데이터 그대로 저장
            actualPreparedStatement.setNString(parameterIndex, result.value);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            }
            throw e;
        }
    }
    
    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        actualPreparedStatement.setNCharacterStream(parameterIndex, value, length);
    }
    
    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        actualPreparedStatement.setNClob(parameterIndex, value);
    }
    
    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        actualPreparedStatement.setClob(parameterIndex, reader, length);
    }
    
    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        actualPreparedStatement.setBlob(parameterIndex, inputStream, length);
    }
    
    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        actualPreparedStatement.setNClob(parameterIndex, reader, length);
    }
    
    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        actualPreparedStatement.setSQLXML(parameterIndex, xmlObject);
    }
    
    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        log.trace("setObject(scale) called: parameterIndex={}, valueType={}, targetSqlType={}, scaleOrLength={}, value={}", parameterIndex,
                 x != null ? x.getClass().getSimpleName() : "null", targetSqlType, scaleOrLength,
                 x instanceof String && ((String)x).length() > 30 ? ((String)x).substring(0, 30) + "..." : x);
        
        // String 타입인 경우에만 암호화 처리 시도
        if (x instanceof String) {
            String stringValue = (String) x;
            try {
                EncryptionResult result = processStringEncryption(parameterIndex, stringValue, "setObject(scale)");
                
                log.trace("setObject(scale) encryption result: parameterIndex={}, shouldSkip={}, value={}", parameterIndex, result.shouldSkip,
                         result.value != null && result.value.length() > 50 ? result.value.substring(0, 50) + "..." : result.value);
                
                if (result.shouldSkip) {
                    log.trace("setObject(scale) skip: parameterIndex={}, using original value", parameterIndex);
                    actualPreparedStatement.setObject(parameterIndex, result.value, targetSqlType, scaleOrLength);
                    return;
                }
                
                // VARCHAR 계열 타입이면 setString 사용 (타입 안전성)
                // 단, scaleOrLength가 지정된 경우는 setObject 사용 (길이 제한이 있을 수 있음)
                if ((targetSqlType == Types.VARCHAR || targetSqlType == Types.NVARCHAR || 
                     targetSqlType == Types.LONGVARCHAR || targetSqlType == Types.CLOB) && scaleOrLength <= 0) {
                    log.trace("setObject(scale) -> delegated to setString: parameterIndex={}, encryptedValue={}", parameterIndex,
                             result.value != null && result.value.length() > 50 ? result.value.substring(0, 50) + "..." : result.value);
                    actualPreparedStatement.setString(parameterIndex, result.value);
                } else {
                    // 암호화된 경우 암호화된 값 사용, 아니면 원본 값 사용
                    log.trace("setObject(scale) set: parameterIndex={}, targetSqlType={}, scaleOrLength={}, encryptedValue={}",
                             parameterIndex, targetSqlType, scaleOrLength,
                             result.value != null && result.value.length() > 50 ? result.value.substring(0, 50) + "..." : result.value);
                    actualPreparedStatement.setObject(parameterIndex, result.value, targetSqlType, scaleOrLength);
                }
                return;
            } catch (RuntimeException e) {
                if (e.getCause() instanceof SQLException) {
                    throw (SQLException) e.getCause();
                }
                throw e;
            }
        }
        
        // String이 아닌 경우 원본 그대로 전달
        log.trace("setObject(scale) not a String: parameterIndex={}, using original value", parameterIndex);
        actualPreparedStatement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }
    
    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        actualPreparedStatement.setAsciiStream(parameterIndex, x, length);
    }
    
    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        actualPreparedStatement.setBinaryStream(parameterIndex, x, length);
    }
    
    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        actualPreparedStatement.setCharacterStream(parameterIndex, reader, length);
    }
    
    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        actualPreparedStatement.setAsciiStream(parameterIndex, x);
    }
    
    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        actualPreparedStatement.setBinaryStream(parameterIndex, x);
    }
    
    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        actualPreparedStatement.setCharacterStream(parameterIndex, reader);
    }
    
    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        actualPreparedStatement.setNCharacterStream(parameterIndex, value);
    }
    
    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        actualPreparedStatement.setClob(parameterIndex, reader);
    }
    
    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        actualPreparedStatement.setBlob(parameterIndex, inputStream);
    }
    
    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        actualPreparedStatement.setNClob(parameterIndex, reader);
    }
    
    // Statement 인터페이스 메서드들
    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        return actualPreparedStatement.executeQuery(sql);
    }
    
    @Override
    public int executeUpdate(String sql) throws SQLException {
        return actualPreparedStatement.executeUpdate(sql);
    }
    
    @Override
    public void close() throws SQLException {
        actualPreparedStatement.close();
    }
    
    @Override
    public int getMaxFieldSize() throws SQLException {
        return actualPreparedStatement.getMaxFieldSize();
    }
    
    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        actualPreparedStatement.setMaxFieldSize(max);
    }
    
    @Override
    public int getMaxRows() throws SQLException {
        return actualPreparedStatement.getMaxRows();
    }
    
    @Override
    public void setMaxRows(int max) throws SQLException {
        actualPreparedStatement.setMaxRows(max);
    }
    
    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        actualPreparedStatement.setEscapeProcessing(enable);
    }
    
    @Override
    public int getQueryTimeout() throws SQLException {
        return actualPreparedStatement.getQueryTimeout();
    }
    
    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        actualPreparedStatement.setQueryTimeout(seconds);
    }
    
    @Override
    public void cancel() throws SQLException {
        actualPreparedStatement.cancel();
    }
    
    @Override
    public SQLWarning getWarnings() throws SQLException {
        return actualPreparedStatement.getWarnings();
    }
    
    @Override
    public void clearWarnings() throws SQLException {
        actualPreparedStatement.clearWarnings();
    }
    
    @Override
    public void setCursorName(String name) throws SQLException {
        actualPreparedStatement.setCursorName(name);
    }
    
    @Override
    public boolean execute(String sql) throws SQLException {
        return actualPreparedStatement.execute(sql);
    }
    
    @Override
    public ResultSet getResultSet() throws SQLException {
        ResultSet actualRs = actualPreparedStatement.getResultSet();
        if (actualRs != null) {
            return new DadpProxyResultSet(actualRs, sql, proxyConnection);
        }
        return null;
    }
    
    @Override
    public int getUpdateCount() throws SQLException {
        return actualPreparedStatement.getUpdateCount();
    }
    
    @Override
    public boolean getMoreResults() throws SQLException {
        return actualPreparedStatement.getMoreResults();
    }
    
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        actualPreparedStatement.setFetchDirection(direction);
    }
    
    @Override
    public int getFetchDirection() throws SQLException {
        return actualPreparedStatement.getFetchDirection();
    }
    
    @Override
    public void setFetchSize(int rows) throws SQLException {
        actualPreparedStatement.setFetchSize(rows);
    }
    
    @Override
    public int getFetchSize() throws SQLException {
        return actualPreparedStatement.getFetchSize();
    }
    
    @Override
    public int getResultSetConcurrency() throws SQLException {
        return actualPreparedStatement.getResultSetConcurrency();
    }
    
    @Override
    public int getResultSetType() throws SQLException {
        return actualPreparedStatement.getResultSetType();
    }
    
    @Override
    public void addBatch(String sql) throws SQLException {
        actualPreparedStatement.addBatch(sql);
    }
    
    @Override
    public void clearBatch() throws SQLException {
        actualPreparedStatement.clearBatch();
    }
    
    @Override
    public int[] executeBatch() throws SQLException {
        return actualPreparedStatement.executeBatch();
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        return proxyConnection;
    }
    
    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return actualPreparedStatement.getMoreResults(current);
    }
    
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        ResultSet actualRs = actualPreparedStatement.getGeneratedKeys();
        if (actualRs != null) {
            return new DadpProxyResultSet(actualRs, sql, proxyConnection);
        }
        return null;
    }
    
    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return actualPreparedStatement.executeUpdate(sql, autoGeneratedKeys);
    }
    
    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return actualPreparedStatement.executeUpdate(sql, columnIndexes);
    }
    
    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return actualPreparedStatement.executeUpdate(sql, columnNames);
    }
    
    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return actualPreparedStatement.execute(sql, autoGeneratedKeys);
    }
    
    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return actualPreparedStatement.execute(sql, columnIndexes);
    }
    
    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return actualPreparedStatement.execute(sql, columnNames);
    }
    
    @Override
    public int getResultSetHoldability() throws SQLException {
        return actualPreparedStatement.getResultSetHoldability();
    }
    
    @Override
    public boolean isClosed() throws SQLException {
        return actualPreparedStatement.isClosed();
    }
    
    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        actualPreparedStatement.setPoolable(poolable);
    }
    
    @Override
    public boolean isPoolable() throws SQLException {
        return actualPreparedStatement.isPoolable();
    }
    
    @Override
    public void closeOnCompletion() throws SQLException {
        actualPreparedStatement.closeOnCompletion();
    }
    
    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return actualPreparedStatement.isCloseOnCompletion();
    }
    
    @Override
    public long getLargeUpdateCount() throws SQLException {
        return actualPreparedStatement.getLargeUpdateCount();
    }
    
    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        actualPreparedStatement.setLargeMaxRows(max);
    }
    
    @Override
    public long getLargeMaxRows() throws SQLException {
        return actualPreparedStatement.getLargeMaxRows();
    }
    
    @Override
    public long[] executeLargeBatch() throws SQLException {
        return actualPreparedStatement.executeLargeBatch();
    }
    
    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        return actualPreparedStatement.executeLargeUpdate(sql);
    }
    
    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return actualPreparedStatement.executeLargeUpdate(sql, autoGeneratedKeys);
    }
    
    @Override
    public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return actualPreparedStatement.executeLargeUpdate(sql, columnIndexes);
    }
    
    @Override
    public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
        return actualPreparedStatement.executeLargeUpdate(sql, columnNames);
    }
    
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return actualPreparedStatement.unwrap(iface);
    }
    
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || actualPreparedStatement.isWrapperFor(iface);
    }

    /**
     * SQL 실행 결과를 통계 앱으로 전송 (Best-effort).
     */
    private void recordTelemetry(long startNanos, boolean errorFlag) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        String sqlType = sqlParseResult != null ? sqlParseResult.getSqlType() : "UNKNOWN";
        proxyConnection.sendSqlTelemetry(sql, sqlType, durationMs, errorFlag);
    }
}

