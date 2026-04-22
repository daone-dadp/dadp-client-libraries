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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private static final int STATEMENT_STRUCTURE_CACHE_MAX_ENTRIES = 512;
    private static final int COMPILED_PLAN_CACHE_MAX_ENTRIES = 1024;
    private static final int NEGATIVE_POLICY_CACHE_MAX_ENTRIES = 2048;
    private static final Object STATEMENT_CACHE_LOCK = new Object();
    private static final Map<String, StatementStructureCacheEntry> STATEMENT_STRUCTURE_CACHE =
            new LinkedHashMap<String, StatementStructureCacheEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StatementStructureCacheEntry> eldest) {
                    return size() > STATEMENT_STRUCTURE_CACHE_MAX_ENTRIES;
                }
            };
    private static final Map<StatementPlanCacheKey, CompiledStatementPlan> COMPILED_PLAN_CACHE =
            new LinkedHashMap<StatementPlanCacheKey, CompiledStatementPlan>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<StatementPlanCacheKey, CompiledStatementPlan> eldest) {
                    return size() > COMPILED_PLAN_CACHE_MAX_ENTRIES;
                }
            };
    private static final Map<NegativePolicyCacheKey, Boolean> NEGATIVE_POLICY_CACHE =
            new LinkedHashMap<NegativePolicyCacheKey, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<NegativePolicyCacheKey, Boolean> eldest) {
                    return size() > NEGATIVE_POLICY_CACHE_MAX_ENTRIES;
                }
            };
    
    private final PreparedStatement actualPreparedStatement;
    private final String sql;
    private final DadpProxyConnection proxyConnection;
    private final StatementStructureCacheEntry statementStructure;
    private final SqlParser.SqlParseResult sqlParseResult;
    private final Map<Integer, String> parameterToColumnMap; // parameterIndex -> columnName
    private final Set<Integer> whereClauseParamIndices; // UPDATE WHERE clause parameter indices (for search encryption routing)
    private final Map<Integer, String> originalDataMap; // parameterIndex -> original plaintext data (for fail-open on truncation)
    private final StatementClassification statementClassification;
    private final boolean allPlaintextStatement;
    private String lastResultSetSql;
    private volatile CompiledStatementPlan compiledStatementPlan;
    private volatile CompiledStatementPlan pinnedStatementPlan;
    
    public DadpProxyPreparedStatement(PreparedStatement actualPs, String sql, DadpProxyConnection proxyConnection) {
        this.actualPreparedStatement = actualPs;
        this.sql = sql;
        this.proxyConnection = proxyConnection;
        this.lastResultSetSql = sql;
        
        this.statementStructure = getOrCreateStatementStructure(sql);
        this.sqlParseResult = statementStructure.sqlParseResult;
        this.whereClauseParamIndices = statementStructure.whereClauseParamIndices;
        this.parameterToColumnMap = statementStructure.parameterToColumnMap;
        
        // 원본 데이터 저장용 맵 초기화 (Data truncation 시 평문으로 재시도)
        this.originalDataMap = new HashMap<>();
        StatementClassification initialClassification = classifyStatementAtPrepareTime();
        if (initialClassification != StatementClassification.ALL_PLAINTEXT) {
            CompiledStatementPlan initialPlan = getCompiledStatementPlan();
            this.pinnedStatementPlan = initialPlan;
            if (initialPlan != null) {
                initialClassification = initialPlan.statementClassification;
            }
        }
        this.statementClassification = initialClassification;
        this.allPlaintextStatement = initialClassification == StatementClassification.ALL_PLAINTEXT;
        
        // INSERT/UPDATE 쿼리인 경우 상세 로그 출력 (디버깅용)
        if (sqlParseResult != null && ("INSERT".equals(sqlParseResult.getSqlType()) || "UPDATE".equals(sqlParseResult.getSqlType()))) {
            log.trace("INSERT/UPDATE PreparedStatement created: sql={}, table={}, columns={}, parameterMapping={}, statementClassification={}",
                    sql, sqlParseResult.getTableName(),
                    sqlParseResult.getColumns() != null ? String.join(", ", sqlParseResult.getColumns()) : "null",
                    parameterToColumnMap,
                    statementClassification);
        } else if (sqlParseResult != null && !parameterToColumnMap.isEmpty()) {
            log.trace("DADP Proxy PreparedStatement created: {} ({} parameter mappings, classification={})",
                    sql, parameterToColumnMap.size(), statementClassification);
        } else {
            log.trace("DADP Proxy PreparedStatement created: {} (classification={})", sql, statementClassification);
        }
    }

    static void clearHotPathCachesForTest() {
        synchronized (STATEMENT_CACHE_LOCK) {
            STATEMENT_STRUCTURE_CACHE.clear();
            COMPILED_PLAN_CACHE.clear();
            NEGATIVE_POLICY_CACHE.clear();
        }
    }

    private static StatementStructureCacheEntry getOrCreateStatementStructure(String sql) {
        String cacheKey = normalizeSqlCacheKey(sql);
        synchronized (STATEMENT_CACHE_LOCK) {
            StatementStructureCacheEntry cached = STATEMENT_STRUCTURE_CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }

            SqlParser sqlParser = new SqlParser();
            SqlParser.SqlParseResult parseResult = sqlParser.parse(sql);
            Set<Integer> whereClauseParamIndices = new HashSet<>();
            Map<Integer, String> parameterMapping = buildParameterMapping(sql, parseResult, whereClauseParamIndices);

            StatementStructureCacheEntry created = new StatementStructureCacheEntry(
                    parseResult,
                    Collections.unmodifiableMap(new HashMap<>(parameterMapping)),
                    Collections.unmodifiableSet(new HashSet<>(whereClauseParamIndices)),
                    buildParameterColumnArray(parameterMapping),
                    buildWhereClauseParameterArray(whereClauseParamIndices));
            STATEMENT_STRUCTURE_CACHE.put(cacheKey, created);
            return created;
        }
    }

    private static String normalizeSqlCacheKey(String sql) {
        return sql == null ? "" : sql.trim();
    }
    
    /**
     * SQL 파싱 결과로부터 파라미터 인덱스와 컬럼명 매핑 생성
     * INSERT/UPDATE: SET 절의 컬럼만 매핑
     * SELECT: WHERE 절의 파라미터도 매핑
     */
    private static Map<Integer, String> buildParameterMapping(String sql, SqlParser.SqlParseResult parseResult,
                                                              Set<Integer> whereClauseParamIndices) {
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
                        if (whereClauseParamIndices != null) {
                            whereClauseParamIndices.add(key);
                        }
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
    private static void parseWhereClauseParameters(String sql, String tableName, Map<Integer, String> mapping) {
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
            "(?:\\w+\\.)?(\\w+)\\s*(like|=|!=|<>|>|<|>=|<=|in|not\\s+in)\\s*(?:concat\\s*\\(\\s*)?\\?",
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
    private static int countParameters(String sql) {
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

    private static String[] buildParameterColumnArray(Map<Integer, String> parameterMapping) {
        int maxIndex = 0;
        for (Integer parameterIndex : parameterMapping.keySet()) {
            if (parameterIndex != null && parameterIndex > maxIndex) {
                maxIndex = parameterIndex;
            }
        }
        String[] columnsByIndex = new String[maxIndex + 1];
        for (Map.Entry<Integer, String> entry : parameterMapping.entrySet()) {
            Integer parameterIndex = entry.getKey();
            if (parameterIndex != null && parameterIndex >= 0 && parameterIndex < columnsByIndex.length) {
                columnsByIndex[parameterIndex] = entry.getValue();
            }
        }
        return columnsByIndex;
    }

    private static boolean[] buildWhereClauseParameterArray(Set<Integer> whereClauseParamIndices) {
        int maxIndex = 0;
        for (Integer parameterIndex : whereClauseParamIndices) {
            if (parameterIndex != null && parameterIndex > maxIndex) {
                maxIndex = parameterIndex;
            }
        }
        boolean[] whereClauseByIndex = new boolean[maxIndex + 1];
        for (Integer parameterIndex : whereClauseParamIndices) {
            if (parameterIndex != null && parameterIndex >= 0 && parameterIndex < whereClauseByIndex.length) {
                whereClauseByIndex[parameterIndex] = true;
            }
        }
        return whereClauseByIndex;
    }
    
    @Override
    public ResultSet executeQuery() throws SQLException {
        long start = System.nanoTime();
        boolean error = false;
        try {
            ResultSet actualRs = actualPreparedStatement.executeQuery();
            lastResultSetSql = sql;
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
                    String paramColumnName = statementStructure.getParameterColumnName(paramIndex);
                    if (paramColumnName != null) {
                        // schemaName 결정
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
                        String policyName = proxyConnection.getPolicyResolver().resolvePolicy(null, normalizedSchemaName, normalizedTableName, normalizedParamColumnName);
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
     * 파라미터별 암호화 계획 캐시.
     * 스키마/정책 버전이 같으면 정규화와 정책 조회를 다시 수행하지 않는다.
     */
    private static class ParameterEncryptionPlan {
        final String schemaName;
        final String tableName;
        final String columnName;
        final String policyName;
        final boolean searchContext;
        final boolean searchEncryptionNeeded;
        final Long policyVersion;

        ParameterEncryptionPlan(String schemaName,
                                String tableName,
                                String columnName,
                                String policyName,
                                boolean searchContext,
                                boolean searchEncryptionNeeded,
                                Long policyVersion) {
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.columnName = columnName;
            this.policyName = policyName;
            this.searchContext = searchContext;
            this.searchEncryptionNeeded = searchEncryptionNeeded;
            this.policyVersion = policyVersion;
        }

        boolean isDirectPassthrough() {
            if (columnName == null || tableName == null) {
                return true;
            }
            if (searchContext) {
                return policyName == null || !searchEncryptionNeeded;
            }
            return policyName == null;
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
    private EncryptionResult processStringEncryption(int parameterIndex, String value, String methodName,
                                                     CompiledStatementPlan statementPlan) {
        // null/빈 문자열 체크 및 SQL 파싱 결과 확인
        if (value == null || value.isEmpty() || sqlParseResult == null) {
            if (value != null && sqlParseResult == null) {
                log.debug("{}: No SQL parse result: cannot determine encryption target, parameterIndex={}", methodName, parameterIndex);
            }
            return new EncryptionResult(value, false);
        }

        ParameterEncryptionPlan plan = resolveParameterEncryptionPlan(parameterIndex, methodName, statementPlan);
        if (plan == null || plan.columnName == null || plan.tableName == null) {
            log.debug("{}: Missing parameter encryption plan: parameterIndex={}", methodName, parameterIndex);
            return new EncryptionResult(value, false);
        }

        if (plan.searchContext) {
            if (plan.policyName == null) {
                log.trace("{}: SELECT WHERE clause: not an encryption target, {}.{}", methodName, plan.tableName, plan.columnName);
                return new EncryptionResult(value, true);
            }

            if (!plan.searchEncryptionNeeded) {
                log.trace("{}: SELECT WHERE clause: search encryption not needed (useIv=true or usePlain=true), {}.{} (policy: {})",
                        methodName, plan.tableName, plan.columnName, plan.policyName);
                return new EncryptionResult(value, true);
            }

            if (value.contains("%") || value.contains("_")) {
                log.trace("{}: SELECT WHERE clause: wildcard detected, plaintext search, {}.{} (policy: {})",
                        methodName, plan.tableName, plan.columnName, plan.policyName);
                return new EncryptionResult(value, true);
            }

            DirectCryptoAdapter adapter = proxyConnection.getDirectCryptoAdapter();
            if (adapter == null || !adapter.isEndpointAvailable()) {
                log.trace("{}: SELECT WHERE clause: adapter not available, plaintext search, {}.{}",
                        methodName, plan.tableName, plan.columnName);
                return new EncryptionResult(value, true);
            }

            try {
                String searchValue = adapter.encryptForSearch(value, plan.policyName);
                boolean encrypted = !value.equals(searchValue);
                if (encrypted) {
                    log.trace("{} search encryption: {}.{} (policy: {})",
                            methodName, plan.tableName, plan.columnName, plan.policyName);
                } else {
                    log.trace("{} search plaintext: {}.{} (policy: {})",
                            methodName, plan.tableName, plan.columnName, plan.policyName);
                }
                return new EncryptionResult(searchValue, false);
            } catch (Exception e) {
                log.warn("{} search encryption failed, using plaintext: {}.{} - {}",
                        methodName, plan.tableName, plan.columnName, e.getMessage());
                return new EncryptionResult(value, true);
            }
        }

        if (plan.policyName == null) {
            log.trace("{}: Not an encryption target: {}.{}", methodName, plan.tableName, plan.columnName);
            return new EncryptionResult(value, false);
        }

        // 암호화 대상: 직접 암복호화 어댑터 사용 (Engine/Gateway 직접 연결)
        DirectCryptoAdapter adapter = proxyConnection.getDirectCryptoAdapter();
        if (adapter == null) {
            log.warn("{}: Direct crypto adapter not initialized: {}.{} (policy: {})",
                    methodName, plan.tableName, plan.columnName, plan.policyName);
            if (proxyConnection.getConfig().isFailOpen()) {
                return new EncryptionResult(value, false);
            } else {
                // SQLException을 RuntimeException으로 감싸서 throw (호출자가 처리)
                throw new RuntimeException(new SQLException("Crypto adapter is not initialized"));
            }
        }
        
        try {
            String encrypted = adapter.encrypt(value, plan.policyName);
            
            // 원본 데이터 저장 (Data truncation 시 평문으로 재시도하기 위해)
            originalDataMap.put(parameterIndex, value);
            
            log.trace("{} encryption completed: {}.{} -> {} (policy: {})", methodName, plan.tableName, plan.columnName,
                     encrypted != null && encrypted.length() > 50 ? encrypted.substring(0, 50) + "..." : encrypted,
                     plan.policyName);
            return new EncryptionResult(encrypted, false);
        } catch (Exception e) {
            // 암호화 실패 시 경고 레벨로 간략하게 출력하고 평문으로 저장
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("{} encryption failed: {}.{} (policy: {}), saving as plaintext - {}",
                     methodName, plan.tableName, plan.columnName, plan.policyName, errorMsg);
            
            // 암복호화 실패 알림은 엔진에서 전송하므로 Wrapper에서는 제거
            
            if (proxyConnection.getConfig().isFailOpen()) {
                return new EncryptionResult(value, false);
            } else {
                // SQLException을 RuntimeException으로 감싸서 throw (호출자가 처리)
                throw new RuntimeException(new SQLException("Encryption failed: " + errorMsg, e));
            }
        }
    }

    private ParameterEncryptionPlan resolveParameterEncryptionPlan(int parameterIndex, String methodName,
                                                                  CompiledStatementPlan statementPlan) {
        if (statementPlan == null) {
            statementPlan = getPinnedStatementPlan();
        }
        if (statementPlan != null) {
            ParameterEncryptionPlan cachedPlan = statementPlan.getParameterPlan(parameterIndex);
            if (cachedPlan != null) {
                return cachedPlan;
            }
        }

        String columnName = statementStructure.getParameterColumnName(parameterIndex);
        String tableName = sqlParseResult.getTableName();

        if ("INSERT".equals(sqlParseResult.getSqlType()) || "UPDATE".equals(sqlParseResult.getSqlType())) {
            log.trace("{} called: parameterIndex={}, columnName={}, tableName={}, sqlType={}",
                    methodName, parameterIndex, columnName, tableName, sqlParseResult.getSqlType());
        }

        if (columnName == null || tableName == null) {
            return new ParameterEncryptionPlan(null, tableName, columnName, null, false, false, null);
        }

        PolicyResolver policyResolver = proxyConnection.getPolicyResolver();
        PolicyResolver.ProtectedColumnIndex protectedColumnIndex =
                policyResolver != null ? policyResolver.getProtectedColumnIndex() : null;
        Long currentPolicyVersion = policyResolver != null ? policyResolver.getCurrentVersion() : null;
        String schemaName = resolveSchemaNameForLookup();

        boolean isSearchContext = "SELECT".equals(sqlParseResult.getSqlType()) ||
                ("UPDATE".equals(sqlParseResult.getSqlType()) && statementStructure.isWhereClauseParameter(parameterIndex));

        String normalizedSchemaName = proxyConnection.normalizeIdentifier(schemaName);
        String normalizedTableName = proxyConnection.normalizeIdentifier(tableName);
        String normalizedColumnName = proxyConnection.normalizeIdentifier(columnName);

        if ("INSERT".equals(sqlParseResult.getSqlType()) || "UPDATE".equals(sqlParseResult.getSqlType())) {
            log.trace("{}: Policy lookup params: schemaName={}->{}, tableName={}->{}, columnName={}->{}",
                    methodName, schemaName, normalizedSchemaName,
                    tableName, normalizedTableName, columnName, normalizedColumnName);
        }

        String policyName = resolvePolicyName(policyResolver, protectedColumnIndex, normalizedSchemaName,
                normalizedTableName, normalizedColumnName, currentPolicyVersion);

        boolean searchEncryptionNeeded = false;
        if (isSearchContext && policyName != null && policyResolver != null) {
            searchEncryptionNeeded = policyResolver.isSearchEncryptionNeeded(policyName);
        }

        if ("INSERT".equals(sqlParseResult.getSqlType()) || "UPDATE".equals(sqlParseResult.getSqlType())) {
            log.trace("{}: Policy check: {}.{} -> policyName={}", methodName, tableName, columnName, policyName);
        }

        ParameterEncryptionPlan plan = new ParameterEncryptionPlan(
                schemaName,
                tableName,
                columnName,
                policyName,
                isSearchContext,
                searchEncryptionNeeded,
                currentPolicyVersion);
        return plan;
    }

    private CompiledStatementPlan getPinnedStatementPlan() {
        CompiledStatementPlan local = pinnedStatementPlan;
        if (local != null) {
            return local;
        }
        local = getCompiledStatementPlan();
        pinnedStatementPlan = local;
        return local;
    }

    private CompiledStatementPlan getCompiledStatementPlan() {
        if (sqlParseResult == null) {
            return null;
        }

        StatementPlanCacheKey cacheKey = createStatementPlanCacheKey();
        if (cacheKey == null) {
            return null;
        }

        CompiledStatementPlan local = compiledStatementPlan;
        if (local != null && local.cacheKey.equals(cacheKey)) {
            return local;
        }

        synchronized (STATEMENT_CACHE_LOCK) {
            CompiledStatementPlan shared = COMPILED_PLAN_CACHE.get(cacheKey);
            if (shared == null) {
                shared = compileStatementPlan(cacheKey);
                COMPILED_PLAN_CACHE.put(cacheKey, shared);
            }
            compiledStatementPlan = shared;
            return shared;
        }
    }

    private StatementPlanCacheKey createStatementPlanCacheKey() {
        String schemaName = resolveSchemaNameForLookup();
        return new StatementPlanCacheKey(
                normalizeSqlCacheKey(sql),
                proxyConnection.normalizeIdentifier(schemaName),
                proxyConnection.getPolicyResolver() != null ? proxyConnection.getPolicyResolver().getCurrentVersion() : null,
                proxyConnection.getDbVendor());
    }

    private CompiledStatementPlan compileStatementPlan(StatementPlanCacheKey cacheKey) {
        ParameterEncryptionPlan[] plansByIndex = new ParameterEncryptionPlan[statementStructure.parameterColumnsByIndex.length];
        boolean allPassthrough = true;
        boolean hasEncryptedWrite = false;
        boolean hasSearchSensitive = false;
        String tableName = sqlParseResult.getTableName();
        PolicyResolver policyResolver = proxyConnection.getPolicyResolver();
        PolicyResolver.ProtectedColumnIndex protectedColumnIndex =
                policyResolver != null ? policyResolver.getProtectedColumnIndex() : null;
        String normalizedTableName = tableName != null ? proxyConnection.normalizeIdentifier(tableName) : null;

        for (int parameterIndex = 1; parameterIndex < statementStructure.parameterColumnsByIndex.length; parameterIndex++) {
            String columnName = statementStructure.parameterColumnsByIndex[parameterIndex];
            if (columnName == null) {
                continue;
            }

            if (tableName == null) {
                plansByIndex[parameterIndex] = new ParameterEncryptionPlan(
                        cacheKey.schemaName, tableName, columnName, null, false, false, cacheKey.policyVersion);
                continue;
            }

            boolean isSearchContext = "SELECT".equals(sqlParseResult.getSqlType())
                    || ("UPDATE".equals(sqlParseResult.getSqlType()) && statementStructure.isWhereClauseParameter(parameterIndex));
            String normalizedColumnName = proxyConnection.normalizeIdentifier(columnName);
            String policyName = resolvePolicyName(policyResolver, protectedColumnIndex,
                    cacheKey.schemaName, normalizedTableName, normalizedColumnName, cacheKey.policyVersion);
            boolean searchEncryptionNeeded = false;
            if (isSearchContext && policyName != null && policyResolver != null) {
                searchEncryptionNeeded = policyResolver.isSearchEncryptionNeeded(policyName);
            }

            ParameterEncryptionPlan plan = new ParameterEncryptionPlan(
                    cacheKey.schemaName,
                    tableName,
                    columnName,
                    policyName,
                    isSearchContext,
                    searchEncryptionNeeded,
                    cacheKey.policyVersion);
            plansByIndex[parameterIndex] = plan;
            if (!plan.isDirectPassthrough()) {
                allPassthrough = false;
            }
            if (policyName != null) {
                if (isSearchContext) {
                    if (searchEncryptionNeeded) {
                        hasSearchSensitive = true;
                    }
                } else {
                    hasEncryptedWrite = true;
                }
            }
        }

        return new CompiledStatementPlan(
                cacheKey,
                plansByIndex,
                allPassthrough,
                resolveStatementClassification(allPassthrough, hasEncryptedWrite, hasSearchSensitive));
    }

    private String resolvePolicyName(PolicyResolver policyResolver,
                                     PolicyResolver.ProtectedColumnIndex protectedColumnIndex,
                                     String normalizedSchemaName,
                                     String normalizedTableName,
                                     String normalizedColumnName,
                                     Long policyVersion) {
        if (policyResolver == null) {
            return null;
        }

        if (protectedColumnIndex != null) {
            return protectedColumnIndex.resolvePolicy(
                    null, normalizedSchemaName, normalizedTableName, normalizedColumnName);
        }

        NegativePolicyCacheKey negativeCacheKey = new NegativePolicyCacheKey(
                normalizedSchemaName, normalizedTableName, normalizedColumnName, policyVersion,
                proxyConnection.getDbVendor());
        synchronized (STATEMENT_CACHE_LOCK) {
            if (NEGATIVE_POLICY_CACHE.containsKey(negativeCacheKey)) {
                return null;
            }
        }

        String policyName = policyResolver.resolvePolicy(null, normalizedSchemaName, normalizedTableName, normalizedColumnName);
        if (policyName == null) {
            synchronized (STATEMENT_CACHE_LOCK) {
                NEGATIVE_POLICY_CACHE.put(negativeCacheKey, Boolean.TRUE);
            }
        }
        return policyName;
    }

    private boolean shouldBypassAllStringProcessing(CompiledStatementPlan statementPlan) {
        return statementPlan != null && statementPlan.allPassthrough;
    }

    private StatementClassification classifyStatementAtPrepareTime() {
        if (sqlParseResult == null || statementStructure.parameterColumnsByIndex.length == 0) {
            return StatementClassification.ALL_PLAINTEXT;
        }

        String tableName = sqlParseResult.getTableName();
        if (tableName == null || tableName.trim().isEmpty()) {
            return StatementClassification.ALL_PLAINTEXT;
        }

        PolicyResolver policyResolver = proxyConnection.getPolicyResolver();
        if (policyResolver == null) {
            return StatementClassification.ALL_PLAINTEXT;
        }

        PolicyResolver.ProtectedColumnIndex protectedColumnIndex = policyResolver.getProtectedColumnIndex();
        if (protectedColumnIndex == null) {
            return StatementClassification.PLAN_REQUIRED;
        }

        String normalizedSchemaName = proxyConnection.normalizeIdentifier(resolveSchemaNameForLookup());
        String normalizedTableName = proxyConnection.normalizeIdentifier(tableName);
        boolean hasEncryptedWrite = false;
        boolean hasSearchSensitive = false;
        boolean requiresPlan = false;

        for (int parameterIndex = 1; parameterIndex < statementStructure.parameterColumnsByIndex.length; parameterIndex++) {
            String columnName = statementStructure.parameterColumnsByIndex[parameterIndex];
            if (columnName == null) {
                continue;
            }

            boolean isSearchContext = "SELECT".equals(sqlParseResult.getSqlType())
                    || ("UPDATE".equals(sqlParseResult.getSqlType()) && statementStructure.isWhereClauseParameter(parameterIndex));
            String normalizedColumnName = proxyConnection.normalizeIdentifier(columnName);
            String policyName = protectedColumnIndex.resolvePolicy(
                    null, normalizedSchemaName, normalizedTableName, normalizedColumnName);
            if (policyName == null) {
                continue;
            }

            if (isSearchContext) {
                if (policyResolver.isSearchEncryptionNeeded(policyName)) {
                    hasSearchSensitive = true;
                    requiresPlan = true;
                }
            } else {
                hasEncryptedWrite = true;
                requiresPlan = true;
            }

            if (hasEncryptedWrite && hasSearchSensitive) {
                return StatementClassification.MIXED;
            }
        }

        if (!requiresPlan) {
            return StatementClassification.ALL_PLAINTEXT;
        }
        if (hasSearchSensitive) {
            return hasEncryptedWrite ? StatementClassification.MIXED : StatementClassification.SEARCH_SENSITIVE;
        }
        return StatementClassification.ENCRYPTED_WRITE;
    }

    private StatementClassification resolveStatementClassification(boolean allPassthrough,
                                                                  boolean hasEncryptedWrite,
                                                                  boolean hasSearchSensitive) {
        if (allPassthrough) {
            return StatementClassification.ALL_PLAINTEXT;
        }
        if (hasEncryptedWrite && hasSearchSensitive) {
            return StatementClassification.MIXED;
        }
        if (hasSearchSensitive) {
            return StatementClassification.SEARCH_SENSITIVE;
        }
        if (hasEncryptedWrite) {
            return StatementClassification.ENCRYPTED_WRITE;
        }
        return StatementClassification.PLAN_REQUIRED;
    }

    private String resolveSchemaNameForLookup() {
        String schemaName = sqlParseResult.getSchemaName();
        if (schemaName == null || schemaName.trim().isEmpty()) {
            schemaName = proxyConnection.getCurrentSchemaName();
            if (schemaName == null || schemaName.trim().isEmpty()) {
                schemaName = proxyConnection.getCurrentDatabaseName();
            }
        }
        return schemaName;
    }

    private static class StatementStructureCacheEntry {
        final SqlParser.SqlParseResult sqlParseResult;
        final Map<Integer, String> parameterToColumnMap;
        final Set<Integer> whereClauseParamIndices;
        final String[] parameterColumnsByIndex;
        final boolean[] whereClauseParamByIndex;

        StatementStructureCacheEntry(SqlParser.SqlParseResult sqlParseResult,
                                     Map<Integer, String> parameterToColumnMap,
                                     Set<Integer> whereClauseParamIndices,
                                     String[] parameterColumnsByIndex,
                                     boolean[] whereClauseParamByIndex) {
            this.sqlParseResult = sqlParseResult;
            this.parameterToColumnMap = parameterToColumnMap;
            this.whereClauseParamIndices = whereClauseParamIndices;
            this.parameterColumnsByIndex = parameterColumnsByIndex;
            this.whereClauseParamByIndex = whereClauseParamByIndex;
        }

        String getParameterColumnName(int parameterIndex) {
            if (parameterIndex >= 0 && parameterIndex < parameterColumnsByIndex.length) {
                return parameterColumnsByIndex[parameterIndex];
            }
            return parameterToColumnMap.get(parameterIndex);
        }

        boolean isWhereClauseParameter(int parameterIndex) {
            if (parameterIndex >= 0 && parameterIndex < whereClauseParamByIndex.length) {
                return whereClauseParamByIndex[parameterIndex];
            }
            return whereClauseParamIndices.contains(parameterIndex);
        }
    }

    private static class StatementPlanCacheKey {
        final String normalizedSql;
        final String schemaName;
        final Long policyVersion;
        final String dbVendor;

        StatementPlanCacheKey(String normalizedSql, String schemaName, Long policyVersion, String dbVendor) {
            this.normalizedSql = normalizedSql;
            this.schemaName = schemaName;
            this.policyVersion = policyVersion;
            this.dbVendor = dbVendor;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof StatementPlanCacheKey)) {
                return false;
            }
            StatementPlanCacheKey that = (StatementPlanCacheKey) o;
            return Objects.equals(normalizedSql, that.normalizedSql)
                    && Objects.equals(schemaName, that.schemaName)
                    && Objects.equals(policyVersion, that.policyVersion)
                    && Objects.equals(dbVendor, that.dbVendor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(normalizedSql, schemaName, policyVersion, dbVendor);
        }
    }

    private static class NegativePolicyCacheKey {
        final String schemaName;
        final String tableName;
        final String columnName;
        final Long policyVersion;
        final String dbVendor;

        NegativePolicyCacheKey(String schemaName,
                               String tableName,
                               String columnName,
                               Long policyVersion,
                               String dbVendor) {
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.columnName = columnName;
            this.policyVersion = policyVersion;
            this.dbVendor = dbVendor;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof NegativePolicyCacheKey)) {
                return false;
            }
            NegativePolicyCacheKey that = (NegativePolicyCacheKey) o;
            return Objects.equals(schemaName, that.schemaName)
                    && Objects.equals(tableName, that.tableName)
                    && Objects.equals(columnName, that.columnName)
                    && Objects.equals(policyVersion, that.policyVersion)
                    && Objects.equals(dbVendor, that.dbVendor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(schemaName, tableName, columnName, policyVersion, dbVendor);
        }
    }

    private enum StatementClassification {
        ALL_PLAINTEXT,
        ENCRYPTED_WRITE,
        SEARCH_SENSITIVE,
        MIXED,
        PLAN_REQUIRED
    }

    private static class CompiledStatementPlan {
        final StatementPlanCacheKey cacheKey;
        final ParameterEncryptionPlan[] parameterPlansByIndex;
        final boolean allPassthrough;
        final StatementClassification statementClassification;

        CompiledStatementPlan(StatementPlanCacheKey cacheKey,
                              ParameterEncryptionPlan[] parameterPlansByIndex,
                              boolean allPassthrough,
                              StatementClassification statementClassification) {
            this.cacheKey = cacheKey;
            this.parameterPlansByIndex = parameterPlansByIndex;
            this.allPassthrough = allPassthrough;
            this.statementClassification = statementClassification;
        }

        ParameterEncryptionPlan getParameterPlan(int parameterIndex) {
            if (parameterIndex >= 0 && parameterIndex < parameterPlansByIndex.length) {
                return parameterPlansByIndex[parameterIndex];
            }
            return null;
        }
    }
    
    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        try {
            if (allPlaintextStatement) {
                actualPreparedStatement.setString(parameterIndex, x);
                return;
            }
            CompiledStatementPlan statementPlan = getPinnedStatementPlan();
            if (shouldBypassAllStringProcessing(statementPlan)) {
                actualPreparedStatement.setString(parameterIndex, x);
                return;
            }

            EncryptionResult result = processStringEncryption(parameterIndex, x, "setString", statementPlan);
            
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
                if (allPlaintextStatement) {
                    actualPreparedStatement.setObject(parameterIndex, x, targetSqlType);
                    return;
                }
                CompiledStatementPlan statementPlan = getPinnedStatementPlan();
                if (shouldBypassAllStringProcessing(statementPlan)) {
                    actualPreparedStatement.setObject(parameterIndex, x, targetSqlType);
                    return;
                }

                EncryptionResult result = processStringEncryption(parameterIndex, stringValue, "setObject(type)", statementPlan);
                
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
                if (allPlaintextStatement) {
                    actualPreparedStatement.setObject(parameterIndex, x);
                    return;
                }
                CompiledStatementPlan statementPlan = getPinnedStatementPlan();
                if (shouldBypassAllStringProcessing(statementPlan)) {
                    actualPreparedStatement.setObject(parameterIndex, x);
                    return;
                }

                EncryptionResult result = processStringEncryption(parameterIndex, stringValue, "setObject", statementPlan);
                
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
        long start = System.nanoTime();
        boolean error = false;
        try {
            boolean result = executeInternal();
            lastResultSetSql = result ? sql : null;
            return result;
        } catch (SQLException e) {
            error = true;
            throw e;
        } finally {
            recordTelemetry(start, error);
        }
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
            if (allPlaintextStatement) {
                actualPreparedStatement.setNString(parameterIndex, value);
                return;
            }
            CompiledStatementPlan statementPlan = getPinnedStatementPlan();
            if (shouldBypassAllStringProcessing(statementPlan)) {
                actualPreparedStatement.setNString(parameterIndex, value);
                return;
            }

            EncryptionResult result = processStringEncryption(parameterIndex, value, "setNString", statementPlan);
            
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
                if (allPlaintextStatement) {
                    actualPreparedStatement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
                    return;
                }
                CompiledStatementPlan statementPlan = getPinnedStatementPlan();
                if (shouldBypassAllStringProcessing(statementPlan)) {
                    actualPreparedStatement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
                    return;
                }

                EncryptionResult result = processStringEncryption(parameterIndex, stringValue, "setObject(scale)", statementPlan);
                
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
        long start = System.nanoTime();
        boolean error = false;
        try {
            ResultSet actualRs = actualPreparedStatement.executeQuery(sql);
            lastResultSetSql = sql;
            return new DadpProxyResultSet(actualRs, sql, proxyConnection);
        } catch (SQLException e) {
            error = true;
            throw e;
        } finally {
            recordTelemetry(sql, start, error);
        }
    }
    
    @Override
    public int executeUpdate(String sql) throws SQLException {
        return executeWithTelemetry(sql, () -> actualPreparedStatement.executeUpdate(sql), false);
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
        return executeWithTelemetry(sql, () -> actualPreparedStatement.execute(sql), true);
    }
    
    @Override
    public ResultSet getResultSet() throws SQLException {
        ResultSet actualRs = actualPreparedStatement.getResultSet();
        if (actualRs != null) {
            return new DadpProxyResultSet(actualRs, lastResultSetSql, proxyConnection);
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
        return executeWithTelemetry(sql, () -> actualPreparedStatement.executeUpdate(sql, autoGeneratedKeys), false);
    }
    
    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeWithTelemetry(sql, () -> actualPreparedStatement.executeUpdate(sql, columnIndexes), false);
    }
    
    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeWithTelemetry(sql, () -> actualPreparedStatement.executeUpdate(sql, columnNames), false);
    }
    
    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return executeWithTelemetry(sql, () -> actualPreparedStatement.execute(sql, autoGeneratedKeys), true);
    }
    
    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return executeWithTelemetry(sql, () -> actualPreparedStatement.execute(sql, columnIndexes), true);
    }
    
    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return executeWithTelemetry(sql, () -> actualPreparedStatement.execute(sql, columnNames), true);
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
    public long executeLargeUpdate() throws SQLException {
        return executeUpdate();
    }
    
    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        return executeWithTelemetry(sql, () -> actualPreparedStatement.executeLargeUpdate(sql), false);
    }
    
    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeWithTelemetry(sql, () -> actualPreparedStatement.executeLargeUpdate(sql, autoGeneratedKeys), false);
    }
    
    @Override
    public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeWithTelemetry(sql, () -> actualPreparedStatement.executeLargeUpdate(sql, columnIndexes), false);
    }
    
    @Override
    public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeWithTelemetry(sql, () -> actualPreparedStatement.executeLargeUpdate(sql, columnNames), false);
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

    private boolean executeInternal() throws SQLException {
        // SQL 파싱 결과를 확인하여 쿼리 타입에 따라 처리
        if (sqlParseResult != null) {
            String sqlType = sqlParseResult.getSqlType();
            
            if ("INSERT".equals(sqlType) || "UPDATE".equals(sqlType) || "DELETE".equals(sqlType)) {
                // INSERT/UPDATE/DELETE인 경우: executeUpdate()와 동일한 Data truncation 처리 로직 적용
                try {
                    return actualPreparedStatement.execute();
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
                                // schemaName 결정
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
                                String policyName = proxyConnection.getPolicyResolver().resolvePolicy(
                                        null, normalizedSchemaName, normalizedTableName, normalizedParamColumnName);
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

    private <T> T executeWithTelemetry(String sqlText, SqlCallable<T> action, boolean captureResultSetSql)
            throws SQLException {
        long start = System.nanoTime();
        boolean error = false;
        try {
            T result = action.call();
            if (captureResultSetSql) {
                boolean hasResultSet = result instanceof Boolean && ((Boolean) result).booleanValue();
                lastResultSetSql = hasResultSet ? sqlText : null;
            } else {
                lastResultSetSql = null;
            }
            return result;
        } catch (SQLException e) {
            error = true;
            throw e;
        } finally {
            recordTelemetry(sqlText, start, error);
        }
    }

    private void recordTelemetry(String sqlText, long startNanos, boolean errorFlag) {
        if (sqlText == null || sqlText.trim().isEmpty()) {
            return;
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        proxyConnection.sendSqlTelemetry(sqlText, extractSqlType(sqlText), durationMs, errorFlag);
    }

    private String extractSqlType(String sqlText) {
        String trimmed = sqlText == null ? "" : sqlText.trim();
        if (trimmed.isEmpty()) {
            return "UNKNOWN";
        }
        int delimiter = trimmed.indexOf(' ');
        String firstToken = delimiter >= 0 ? trimmed.substring(0, delimiter) : trimmed;
        return firstToken.toUpperCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface SqlCallable<T> {
        T call() throws SQLException;
    }
}
