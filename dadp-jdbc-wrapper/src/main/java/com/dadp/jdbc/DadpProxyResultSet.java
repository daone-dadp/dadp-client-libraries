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
import java.util.Map;
import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;

/**
 * DADP Proxy ResultSet
 * 
 * ResultSet을 래핑하여 결과셋 조회 시 복호화 처리를 수행합니다.
 * 
 * @author DADP Development Team
 * @version 4.8.0
 * @since 2025-11-07
 */
public class DadpProxyResultSet implements ResultSet {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(DadpProxyResultSet.class);
    
    private final ResultSet actualResultSet;
    private final String sql;
    private final DadpProxyConnection proxyConnection;
    private final SqlParser.SqlParseResult sqlParseResult;
    
    public DadpProxyResultSet(ResultSet actualRs, String sql, DadpProxyConnection proxyConnection) {
        this.actualResultSet = actualRs;
        this.sql = sql;
        this.proxyConnection = proxyConnection;
        
        // SQL 파싱 (SELECT 쿼리의 경우 테이블명과 컬럼명 추출)
        SqlParser sqlParser = new SqlParser();
        this.sqlParseResult = sqlParser.parse(sql);
        
        log.info("🔍 DADP Proxy ResultSet 생성: table={}", 
                 sqlParseResult != null ? sqlParseResult.getTableName() : "null");
    }
    
    @Override
    public boolean next() throws SQLException {
        return actualResultSet.next();
    }
    
    @Override
    public void close() throws SQLException {
        actualResultSet.close();
    }
    
    @Override
    public boolean isClosed() throws SQLException {
        return actualResultSet.isClosed();
    }
    
    @Override
    public boolean wasNull() throws SQLException {
        return actualResultSet.wasNull();
    }
    
    @Override
    public SQLWarning getWarnings() throws SQLException {
        return actualResultSet.getWarnings();
    }
    
    @Override
    public void clearWarnings() throws SQLException {
        actualResultSet.clearWarnings();
    }
    
    @Override
    public String getString(int columnIndex) throws SQLException {
        String value = actualResultSet.getString(columnIndex);
        log.debug("🔓 getString(int) 호출: columnIndex={}, valueLength={}", 
                 columnIndex, value != null ? value.length() : 0);
        
        if (value == null) {
            return value;
        }
        
        if (sqlParseResult == null) {
            log.warn("⚠️ SQL 파싱 결과 없음: 복호화 대상 확인 불가, columnIndex={}", columnIndex);
            return value;
        }
        
        try {
            // ResultSetMetaData로 컬럼명 조회
            ResultSetMetaData metaData = actualResultSet.getMetaData();
            String columnName = metaData.getColumnName(columnIndex);
            String columnLabel = metaData.getColumnLabel(columnIndex);
            String tableName = sqlParseResult.getTableName();
            
            log.debug("🔓 복호화 확인: tableName={}, columnName={}, columnLabel={}, columnIndex={}", 
                     tableName, columnName, columnLabel, columnIndex);
            
            if (columnName != null && tableName != null) {
                // 컬럼명에서 테이블 별칭 제거 (u1_0.email -> email)
                if (columnName.contains(".")) {
                    columnName = columnName.substring(columnName.lastIndexOf('.') + 1);
                }
                
                // Hibernate alias 매핑 확인 (email3_0_ → email)
                // columnLabel이 alias인 경우 원본 컬럼명으로 변환
                String originalColumnName = sqlParseResult.getOriginalColumnName(columnLabel);
                if (!originalColumnName.equals(columnLabel)) {
                    log.debug("🔓 alias 변환: {} → {}", columnLabel, originalColumnName);
                    columnName = originalColumnName;
                } else if (!columnName.equalsIgnoreCase(columnLabel)) {
                    // columnName과 columnLabel이 다르면 alias일 수 있음
                    // 추가로 columnName 기반으로도 매핑 시도
                    String mappedName = sqlParseResult.getOriginalColumnName(columnName);
                    if (!mappedName.equals(columnName)) {
                        log.debug("🔓 alias 변환 (columnName): {} → {}", columnName, mappedName);
                        columnName = mappedName;
                    }
                }
                
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
                String normalizedColumnName = proxyConnection.normalizeIdentifier(columnName);
                
                // PolicyResolver에서 정책 확인 (메모리 캐시에서 조회)
                PolicyResolver policyResolver = proxyConnection.getPolicyResolver();
                String policyName = policyResolver.resolvePolicy(datasourceId, normalizedSchemaName, normalizedTableName, normalizedColumnName);
                
                log.trace("🔓 정책 확인: {}.{}.{} → {}", schemaName != null ? schemaName + "." : "", tableName, columnName, policyName);
                
                if (policyName != null) {
                    // 복호화 대상: Hub를 통해 복호화
                    // 직접 암복호화 어댑터 사용 (Engine/Gateway 직접 연결)
                    DirectCryptoAdapter adapter = proxyConnection.getDirectCryptoAdapter();
                    if (adapter != null) {
                        // DirectCryptoAdapter에서 에러 처리 및 로그 출력 담당
                        long t0 = System.currentTimeMillis();
                        String decrypted = adapter.decrypt(value);
                        long t1 = System.currentTimeMillis();
                        long engineTime = t1 - t0;
                        
                        log.debug("[Wrapper Decrypt] engine={} ms, table={}, column={}", engineTime, tableName, columnName);
                        
                        // decrypted는 null이거나 원본 데이터 (DirectCryptoAdapter에서 처리)
                        if (decrypted != null) {
                            log.debug("🔓 복호화 완료: {}.{} → {} (정책: {})", tableName, columnName, 
                                     decrypted.length() > 20 ? decrypted.substring(0, 20) + "..." : decrypted, 
                                     policyName);
                            return decrypted;
                        }
                        // value가 null인 경우 원본 반환
                    } else {
                        log.warn("⚠️ 암복호화 어댑터가 초기화되지 않았습니다. 암호화된 데이터 반환: {}.{} (정책: {})", 
                                tableName, columnName, policyName);
                    }
                } else {
                    log.trace("🔓 복호화 대상 아님: {}.{}", tableName, columnName);
                }
            } else {
                    log.warn("⚠️ 테이블명 또는 컬럼명 없음: 복호화 대상 확인 불가, tableName={}, columnName={}", tableName, columnName);
                }
            } catch (SQLException e) {
                log.warn("⚠️ 컬럼 메타데이터 조회 실패, 원본 데이터 반환: {}", e.getMessage());
            }
            
            log.trace("🔓 getString 호출: columnIndex={}", columnIndex);
        return value;
    }
    
    @Override
    public String getString(String columnLabel) throws SQLException {
        String value = actualResultSet.getString(columnLabel);
        log.info("🔓 getString(String) 호출: columnLabel={}, valueLength={}", 
                 columnLabel, value != null ? value.length() : 0);
        
        if (value != null && sqlParseResult != null) {
            try {
                String tableName = sqlParseResult.getTableName();
                
                // Hibernate alias 매핑: email3_0_ → email
                String originalColumnName = sqlParseResult.getOriginalColumnName(columnLabel);
                String columnName = (originalColumnName != null && !originalColumnName.equals(columnLabel)) 
                    ? originalColumnName : columnLabel;
                
                if (!columnName.equals(columnLabel)) {
                    log.debug("🔓 alias 변환: {} → {}", columnLabel, columnName);
                }
                
                if (tableName == null) {
                    log.warn("⚠️ 테이블명 없음: 복호화 대상 확인 불가, columnLabel={}", columnLabel);
                } else {
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
                    String normalizedColumnName = proxyConnection.normalizeIdentifier(columnName);
                    
                    // PolicyResolver에서 정책 확인 (메모리 캐시에서 조회)
                    PolicyResolver policyResolver = proxyConnection.getPolicyResolver();
                    String policyName = policyResolver.resolvePolicy(datasourceId, normalizedSchemaName, normalizedTableName, normalizedColumnName);
                    
                    if (policyName != null) {
                        // 복호화 대상: Hub를 통해 복호화
                        // 직접 암복호화 어댑터 사용 (Engine/Gateway 직접 연결)
                        DirectCryptoAdapter adapter = proxyConnection.getDirectCryptoAdapter();
                        if (adapter != null) {
                            // DirectCryptoAdapter에서 에러 처리 및 로그 출력 담당
                            String decrypted = adapter.decrypt(value);
                            // decrypted는 null이거나 원본 데이터 (DirectCryptoAdapter에서 처리)
                            if (decrypted != null) {
                                log.debug("🔓 복호화 완료: {}.{} → {} (정책: {})", tableName, columnName, 
                                         decrypted.length() > 20 ? decrypted.substring(0, 20) + "..." : decrypted, 
                                         policyName);
                                return decrypted;
                            }
                            // value가 null인 경우 원본 반환
                        } else {
                            log.warn("⚠️ Hub 어댑터가 초기화되지 않았습니다: {}.{} (정책: {}), 원본 데이터 반환", 
                                    tableName, columnName, policyName);
                        }
                    } else {
                        log.trace("🔓 복호화 대상 아님: {}.{}", tableName, columnName);
                    }
                }
            } catch (Exception e) {
                // 복호화 처리 중 오류 발생 시 경고 레벨로 간략하게 출력하고 평문 반환
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("⚠️ 복호화 처리 중 오류, 평문 반환: {}", errorMsg);
            }
        } else if (value != null && sqlParseResult == null) {
            log.warn("⚠️ SQL 파싱 결과 없음: 복호화 대상 확인 불가, columnLabel={}", columnLabel);
        }
        
        log.trace("🔓 getString 호출: columnLabel={}", columnLabel);
        return value;
    }
    
    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return actualResultSet.getBoolean(columnIndex);
    }
    
    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return actualResultSet.getByte(columnIndex);
    }
    
    @Override
    public short getShort(int columnIndex) throws SQLException {
        return actualResultSet.getShort(columnIndex);
    }
    
    @Override
    public int getInt(int columnIndex) throws SQLException {
        return actualResultSet.getInt(columnIndex);
    }
    
    @Override
    public long getLong(int columnIndex) throws SQLException {
        return actualResultSet.getLong(columnIndex);
    }
    
    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return actualResultSet.getFloat(columnIndex);
    }
    
    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return actualResultSet.getDouble(columnIndex);
    }
    
    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        return actualResultSet.getBigDecimal(columnIndex, scale);
    }
    
    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        return actualResultSet.getBytes(columnIndex);
    }
    
    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return actualResultSet.getDate(columnIndex);
    }
    
    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return actualResultSet.getTime(columnIndex);
    }
    
    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return actualResultSet.getTimestamp(columnIndex);
    }
    
    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        return actualResultSet.getAsciiStream(columnIndex);
    }
    
    @Override
    @Deprecated
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        return actualResultSet.getUnicodeStream(columnIndex);
    }
    
    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        return actualResultSet.getBinaryStream(columnIndex);
    }
    
    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return actualResultSet.getBoolean(columnLabel);
    }
    
    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return actualResultSet.getByte(columnLabel);
    }
    
    @Override
    public short getShort(String columnLabel) throws SQLException {
        return actualResultSet.getShort(columnLabel);
    }
    
    @Override
    public int getInt(String columnLabel) throws SQLException {
        return actualResultSet.getInt(columnLabel);
    }
    
    @Override
    public long getLong(String columnLabel) throws SQLException {
        return actualResultSet.getLong(columnLabel);
    }
    
    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return actualResultSet.getFloat(columnLabel);
    }
    
    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return actualResultSet.getDouble(columnLabel);
    }
    
    @Override
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return actualResultSet.getBigDecimal(columnLabel, scale);
    }
    
    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return actualResultSet.getBytes(columnLabel);
    }
    
    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return actualResultSet.getDate(columnLabel);
    }
    
    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return actualResultSet.getTime(columnLabel);
    }
    
    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return actualResultSet.getTimestamp(columnLabel);
    }
    
    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return actualResultSet.getAsciiStream(columnLabel);
    }
    
    @Override
    @Deprecated
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return actualResultSet.getUnicodeStream(columnLabel);
    }
    
    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return actualResultSet.getBinaryStream(columnLabel);
    }
    
    @Override
    public Object getObject(int columnIndex) throws SQLException {
        Object value = actualResultSet.getObject(columnIndex);
        log.debug("🔍 getObject(int) 호출: columnIndex={}, type={}", columnIndex, 
                  value != null ? value.getClass().getSimpleName() : "null");
        
        // String 타입인 경우 복호화 처리
        if (value instanceof String) {
            return decryptIfNeeded(columnIndex, (String) value);
        }
        return value;
    }
    
    @Override
    public Object getObject(String columnLabel) throws SQLException {
        Object value = actualResultSet.getObject(columnLabel);
        log.debug("🔍 getObject(String) 호출: columnLabel={}, type={}", columnLabel,
                  value != null ? value.getClass().getSimpleName() : "null");
        
        // String 타입인 경우 복호화 처리
        if (value instanceof String) {
            return decryptStringByLabel(columnLabel, (String) value);
        }
        return value;
    }
    
    /**
     * 컬럼 인덱스로 복호화 처리
     */
    private String decryptIfNeeded(int columnIndex, String value) throws SQLException {
        log.debug("🔓 decryptIfNeeded 호출: columnIndex={}, valueLength={}", 
                  columnIndex, value != null ? value.length() : 0);
        
        if (value == null || sqlParseResult == null) {
            log.debug("🔓 decryptIfNeeded 스킵: value={}, sqlParseResult={}", 
                      value == null ? "null" : "exists", sqlParseResult == null ? "null" : "exists");
            return value;
        }
        
        try {
            ResultSetMetaData metaData = actualResultSet.getMetaData();
            String columnName = metaData.getColumnName(columnIndex);
            String tableName = sqlParseResult.getTableName();
            
            log.debug("🔓 decryptIfNeeded: tableName={}, columnName={}", tableName, columnName);
            return decryptValue(tableName, columnName, value);
        } catch (SQLException e) {
            log.warn("⚠️ 컬럼 메타데이터 조회 실패, 원본 데이터 반환: {}", e.getMessage());
            return value;
        }
    }
    
    /**
     * 컬럼 레이블로 복호화 처리
     */
    private String decryptStringByLabel(String columnLabel, String value) {
        if (value == null || sqlParseResult == null) {
            return value;
        }
        
        String tableName = sqlParseResult.getTableName();
        
        // Hibernate alias 매핑: email3_0_ → email
        String originalColumnName = sqlParseResult.getOriginalColumnName(columnLabel);
        String columnName = (originalColumnName != null && !originalColumnName.equals(columnLabel)) 
            ? originalColumnName : columnLabel;
        
        if (!columnName.equals(columnLabel)) {
            log.debug("🔓 alias 변환 (byLabel): {} → {}", columnLabel, columnName);
        }
        
        return decryptValue(tableName, columnName, value);
    }
    
    /**
     * 실제 복호화 수행
     */
    private String decryptValue(String tableName, String columnName, String value) {
        if (tableName == null || columnName == null) {
            log.debug("🔓 복호화 스킵: tableName={}, columnName={} (null 값)", tableName, columnName);
            return value;
        }
        
        // 컬럼명에서 테이블 별칭 제거
        if (columnName.contains(".")) {
            columnName = columnName.substring(columnName.lastIndexOf('.') + 1);
        }
        
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
        String normalizedColumnName = proxyConnection.normalizeIdentifier(columnName);
        
        // PolicyResolver에서 정책 확인
        PolicyResolver policyResolver = proxyConnection.getPolicyResolver();
        log.debug("🔍 복호화 정책 조회: {}.{}.{}", normalizedSchemaName != null ? normalizedSchemaName + "." : "", normalizedTableName, normalizedColumnName);
        String policyName = policyResolver.resolvePolicy(datasourceId, normalizedSchemaName, normalizedTableName, normalizedColumnName);
        
        if (policyName != null) {
            log.debug("🔓 복호화 대상: {}.{}, 정책={}", tableName, columnName, policyName);
            // 직접 암복호화 어댑터 사용 (Engine/Gateway 직접 연결)
            DirectCryptoAdapter adapter = proxyConnection.getDirectCryptoAdapter();
            if (adapter != null) {
                long t0 = System.currentTimeMillis();
                // DirectCryptoAdapter에서 에러 처리 및 로그 출력 담당
                String decrypted = adapter.decrypt(value);
                long t1 = System.currentTimeMillis();
                long engineTime = t1 - t0;
                
                log.debug("[Wrapper Decrypt] engine={} ms, table={}, column={}", engineTime, tableName, columnName);
                
                // decrypted는 null이거나 원본 데이터 (DirectCryptoAdapter에서 처리)
                if (decrypted != null) {
                    log.debug("🔓 복호화 완료: {}.{} → {} (정책: {})", tableName, columnName, 
                             decrypted.length() > 20 ? decrypted.substring(0, 20) + "..." : decrypted, 
                             policyName);
                    return decrypted;
                } else {
                    log.debug("🔓 복호화 결과 null (암호화되지 않은 데이터일 수 있음): {}.{}", tableName, columnName);
                }
            } else {
                log.warn("⚠️ 암복호화 어댑터가 초기화되지 않았습니다: {}.{}", tableName, columnName);
            }
        } else {
            log.debug("🔓 복호화 정책 없음: {}.{} (정책 매핑에 등록되지 않음)", tableName, columnName);
        }
        
        return value;
    }
    
    @Override
    public int findColumn(String columnLabel) throws SQLException {
        return actualResultSet.findColumn(columnLabel);
    }
    
    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        return actualResultSet.getCharacterStream(columnIndex);
    }
    
    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return actualResultSet.getCharacterStream(columnLabel);
    }
    
    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return actualResultSet.getBigDecimal(columnIndex);
    }
    
    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return actualResultSet.getBigDecimal(columnLabel);
    }
    
    @Override
    public boolean isBeforeFirst() throws SQLException {
        return actualResultSet.isBeforeFirst();
    }
    
    @Override
    public boolean isAfterLast() throws SQLException {
        return actualResultSet.isAfterLast();
    }
    
    @Override
    public boolean isFirst() throws SQLException {
        return actualResultSet.isFirst();
    }
    
    @Override
    public boolean isLast() throws SQLException {
        return actualResultSet.isLast();
    }
    
    @Override
    public void beforeFirst() throws SQLException {
        actualResultSet.beforeFirst();
    }
    
    @Override
    public void afterLast() throws SQLException {
        actualResultSet.afterLast();
    }
    
    @Override
    public boolean first() throws SQLException {
        return actualResultSet.first();
    }
    
    @Override
    public boolean last() throws SQLException {
        return actualResultSet.last();
    }
    
    @Override
    public int getRow() throws SQLException {
        return actualResultSet.getRow();
    }
    
    @Override
    public boolean absolute(int row) throws SQLException {
        return actualResultSet.absolute(row);
    }
    
    @Override
    public boolean relative(int rows) throws SQLException {
        return actualResultSet.relative(rows);
    }
    
    @Override
    public boolean previous() throws SQLException {
        return actualResultSet.previous();
    }
    
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        actualResultSet.setFetchDirection(direction);
    }
    
    @Override
    public int getFetchDirection() throws SQLException {
        return actualResultSet.getFetchDirection();
    }
    
    @Override
    public void setFetchSize(int rows) throws SQLException {
        actualResultSet.setFetchSize(rows);
    }
    
    @Override
    public int getFetchSize() throws SQLException {
        return actualResultSet.getFetchSize();
    }
    
    @Override
    public String getCursorName() throws SQLException {
        return actualResultSet.getCursorName();
    }
    
    @Override
    public int getType() throws SQLException {
        return actualResultSet.getType();
    }
    
    @Override
    public int getConcurrency() throws SQLException {
        return actualResultSet.getConcurrency();
    }
    
    @Override
    public boolean rowUpdated() throws SQLException {
        return actualResultSet.rowUpdated();
    }
    
    @Override
    public boolean rowInserted() throws SQLException {
        return actualResultSet.rowInserted();
    }
    
    @Override
    public boolean rowDeleted() throws SQLException {
        return actualResultSet.rowDeleted();
    }
    
    @Override
    public void updateNull(int columnIndex) throws SQLException {
        actualResultSet.updateNull(columnIndex);
    }
    
    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        actualResultSet.updateBoolean(columnIndex, x);
    }
    
    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        actualResultSet.updateByte(columnIndex, x);
    }
    
    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        actualResultSet.updateShort(columnIndex, x);
    }
    
    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        actualResultSet.updateInt(columnIndex, x);
    }
    
    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        actualResultSet.updateLong(columnIndex, x);
    }
    
    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        actualResultSet.updateFloat(columnIndex, x);
    }
    
    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        actualResultSet.updateDouble(columnIndex, x);
    }
    
    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        actualResultSet.updateBigDecimal(columnIndex, x);
    }
    
    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        // TODO: 암호화 처리
        actualResultSet.updateString(columnIndex, x);
    }
    
    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        actualResultSet.updateBytes(columnIndex, x);
    }
    
    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        actualResultSet.updateDate(columnIndex, x);
    }
    
    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        actualResultSet.updateTime(columnIndex, x);
    }
    
    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        actualResultSet.updateTimestamp(columnIndex, x);
    }
    
    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        actualResultSet.updateAsciiStream(columnIndex, x, length);
    }
    
    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        actualResultSet.updateBinaryStream(columnIndex, x, length);
    }
    
    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        actualResultSet.updateCharacterStream(columnIndex, x, length);
    }
    
    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        actualResultSet.updateObject(columnIndex, x, scaleOrLength);
    }
    
    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        actualResultSet.updateObject(columnIndex, x);
    }
    
    @Override
    public void updateNull(String columnLabel) throws SQLException {
        actualResultSet.updateNull(columnLabel);
    }
    
    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        actualResultSet.updateBoolean(columnLabel, x);
    }
    
    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        actualResultSet.updateByte(columnLabel, x);
    }
    
    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        actualResultSet.updateShort(columnLabel, x);
    }
    
    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        actualResultSet.updateInt(columnLabel, x);
    }
    
    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        actualResultSet.updateLong(columnLabel, x);
    }
    
    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        actualResultSet.updateFloat(columnLabel, x);
    }
    
    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        actualResultSet.updateDouble(columnLabel, x);
    }
    
    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        actualResultSet.updateBigDecimal(columnLabel, x);
    }
    
    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        // TODO: 암호화 처리
        actualResultSet.updateString(columnLabel, x);
    }
    
    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        actualResultSet.updateBytes(columnLabel, x);
    }
    
    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        actualResultSet.updateDate(columnLabel, x);
    }
    
    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        actualResultSet.updateTime(columnLabel, x);
    }
    
    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        actualResultSet.updateTimestamp(columnLabel, x);
    }
    
    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        actualResultSet.updateAsciiStream(columnLabel, x, length);
    }
    
    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        actualResultSet.updateBinaryStream(columnLabel, x, length);
    }
    
    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        actualResultSet.updateCharacterStream(columnLabel, reader, length);
    }
    
    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        actualResultSet.updateObject(columnLabel, x, scaleOrLength);
    }
    
    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        actualResultSet.updateObject(columnLabel, x);
    }
    
    @Override
    public void insertRow() throws SQLException {
        actualResultSet.insertRow();
    }
    
    @Override
    public void updateRow() throws SQLException {
        actualResultSet.updateRow();
    }
    
    @Override
    public void deleteRow() throws SQLException {
        actualResultSet.deleteRow();
    }
    
    @Override
    public void refreshRow() throws SQLException {
        actualResultSet.refreshRow();
    }
    
    @Override
    public void cancelRowUpdates() throws SQLException {
        actualResultSet.cancelRowUpdates();
    }
    
    @Override
    public void moveToInsertRow() throws SQLException {
        actualResultSet.moveToInsertRow();
    }
    
    @Override
    public void moveToCurrentRow() throws SQLException {
        actualResultSet.moveToCurrentRow();
    }
    
    @Override
    public Statement getStatement() throws SQLException {
        return actualResultSet.getStatement();
    }
    
    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return actualResultSet.getObject(columnIndex, map);
    }
    
    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        return actualResultSet.getRef(columnIndex);
    }
    
    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        return actualResultSet.getBlob(columnIndex);
    }
    
    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        return actualResultSet.getClob(columnIndex);
    }
    
    @Override
    public Array getArray(int columnIndex) throws SQLException {
        return actualResultSet.getArray(columnIndex);
    }
    
    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return actualResultSet.getObject(columnLabel, map);
    }
    
    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return actualResultSet.getRef(columnLabel);
    }
    
    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return actualResultSet.getBlob(columnLabel);
    }
    
    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return actualResultSet.getClob(columnLabel);
    }
    
    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return actualResultSet.getArray(columnLabel);
    }
    
    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        return actualResultSet.getDate(columnIndex, cal);
    }
    
    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return actualResultSet.getDate(columnLabel, cal);
    }
    
    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        return actualResultSet.getTime(columnIndex, cal);
    }
    
    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return actualResultSet.getTime(columnLabel, cal);
    }
    
    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        return actualResultSet.getTimestamp(columnIndex, cal);
    }
    
    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return actualResultSet.getTimestamp(columnLabel, cal);
    }
    
    @Override
    public URL getURL(int columnIndex) throws SQLException {
        return actualResultSet.getURL(columnIndex);
    }
    
    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return actualResultSet.getURL(columnLabel);
    }
    
    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        actualResultSet.updateRef(columnIndex, x);
    }
    
    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        actualResultSet.updateRef(columnLabel, x);
    }
    
    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        actualResultSet.updateBlob(columnIndex, x);
    }
    
    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        actualResultSet.updateBlob(columnLabel, x);
    }
    
    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        actualResultSet.updateClob(columnIndex, x);
    }
    
    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        actualResultSet.updateClob(columnLabel, x);
    }
    
    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        actualResultSet.updateArray(columnIndex, x);
    }
    
    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        actualResultSet.updateArray(columnLabel, x);
    }
    
    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        return actualResultSet.getRowId(columnIndex);
    }
    
    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return actualResultSet.getRowId(columnLabel);
    }
    
    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        actualResultSet.updateRowId(columnIndex, x);
    }
    
    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        actualResultSet.updateRowId(columnLabel, x);
    }
    
    @Override
    public int getHoldability() throws SQLException {
        return actualResultSet.getHoldability();
    }
    
    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        actualResultSet.updateNString(columnIndex, nString);
    }
    
    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        actualResultSet.updateNString(columnLabel, nString);
    }
    
    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        actualResultSet.updateNClob(columnIndex, nClob);
    }
    
    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        actualResultSet.updateNClob(columnLabel, nClob);
    }
    
    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        return actualResultSet.getNClob(columnIndex);
    }
    
    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return actualResultSet.getNClob(columnLabel);
    }
    
    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        return actualResultSet.getSQLXML(columnIndex);
    }
    
    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return actualResultSet.getSQLXML(columnLabel);
    }
    
    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        actualResultSet.updateSQLXML(columnIndex, xmlObject);
    }
    
    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        actualResultSet.updateSQLXML(columnLabel, xmlObject);
    }
    
    @Override
    public String getNString(int columnIndex) throws SQLException {
        String value = actualResultSet.getNString(columnIndex);
        // TODO: 복호화 처리 (getString과 동일)
        return value;
    }
    
    @Override
    public String getNString(String columnLabel) throws SQLException {
        String value = actualResultSet.getNString(columnLabel);
        // TODO: 복호화 처리 (getString과 동일)
        return value;
    }
    
    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return actualResultSet.getNCharacterStream(columnIndex);
    }
    
    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return actualResultSet.getNCharacterStream(columnLabel);
    }
    
    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        actualResultSet.updateNCharacterStream(columnIndex, x, length);
    }
    
    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        actualResultSet.updateNCharacterStream(columnLabel, reader, length);
    }
    
    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        actualResultSet.updateAsciiStream(columnIndex, x, length);
    }
    
    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        actualResultSet.updateBinaryStream(columnIndex, x, length);
    }
    
    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        actualResultSet.updateCharacterStream(columnIndex, x, length);
    }
    
    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        actualResultSet.updateAsciiStream(columnLabel, x, length);
    }
    
    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        actualResultSet.updateBinaryStream(columnLabel, x, length);
    }
    
    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        actualResultSet.updateCharacterStream(columnLabel, reader, length);
    }
    
    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        actualResultSet.updateBlob(columnIndex, inputStream, length);
    }
    
    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        actualResultSet.updateBlob(columnLabel, inputStream, length);
    }
    
    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        actualResultSet.updateClob(columnIndex, reader, length);
    }
    
    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        actualResultSet.updateClob(columnLabel, reader, length);
    }
    
    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        actualResultSet.updateNClob(columnIndex, reader, length);
    }
    
    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        actualResultSet.updateNClob(columnLabel, reader, length);
    }
    
    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        actualResultSet.updateNCharacterStream(columnIndex, x);
    }
    
    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        actualResultSet.updateNCharacterStream(columnLabel, reader);
    }
    
    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        actualResultSet.updateAsciiStream(columnIndex, x);
    }
    
    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        actualResultSet.updateBinaryStream(columnIndex, x);
    }
    
    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        actualResultSet.updateCharacterStream(columnIndex, x);
    }
    
    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        actualResultSet.updateAsciiStream(columnLabel, x);
    }
    
    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        actualResultSet.updateBinaryStream(columnLabel, x);
    }
    
    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        actualResultSet.updateCharacterStream(columnLabel, reader);
    }
    
    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        actualResultSet.updateBlob(columnIndex, inputStream);
    }
    
    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        actualResultSet.updateBlob(columnLabel, inputStream);
    }
    
    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        actualResultSet.updateClob(columnIndex, reader);
    }
    
    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        actualResultSet.updateClob(columnLabel, reader);
    }
    
    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        actualResultSet.updateNClob(columnIndex, reader);
    }
    
    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        actualResultSet.updateNClob(columnLabel, reader);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        log.info("🔓 getObject(int, Class) 호출: columnIndex={}, type={}", columnIndex, type.getSimpleName());
        // String 타입인 경우 복호화 처리
        if (type == String.class) {
            String value = actualResultSet.getString(columnIndex);
            return (T) decryptIfNeeded(columnIndex, value);
        }
        return actualResultSet.getObject(columnIndex, type);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        log.info("🔓 getObject(String, Class) 호출: columnLabel={}, type={}", columnLabel, type.getSimpleName());
        // String 타입인 경우 복호화 처리
        if (type == String.class) {
            String value = actualResultSet.getString(columnLabel);
            return (T) decryptStringByLabel(columnLabel, value);
        }
        return actualResultSet.getObject(columnLabel, type);
    }
    
    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return actualResultSet.getMetaData();
    }
    
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return actualResultSet.unwrap(iface);
    }
    
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || actualResultSet.isWrapperFor(iface);
    }
}

