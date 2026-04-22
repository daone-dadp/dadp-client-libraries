package com.dadp.jdbc;

import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;
import java.sql.*;
import java.util.Properties;

/**
 * DADP JDBC Wrapper Driver
 * 
 * JDBC URL 형식: jdbc:dadp:mysql://... 또는 jdbc:dadp:postgresql://...
 * 실제 DB URL로 변환하여 실제 Driver로 연결을 위임합니다.
 * 
 * @author DADP Development Team
 * @version 3.0.0
 * @since 2025-11-07
 */
public class DadpJdbcDriver implements Driver {
    
    private static final DadpLogger log = DadpLoggerFactory.getLogger(DadpJdbcDriver.class);
    
    private static final int MAJOR_VERSION = 3;
    private static final int MINOR_VERSION = 0;
    
    static {
        try {
            DriverManager.registerDriver(new DadpJdbcDriver());
            log.info("DADP JDBC Driver registered");
        } catch (SQLException e) {
            log.error("DADP JDBC Driver registration failed", e);
            throw new RuntimeException("DADP JDBC Driver registration failed", e);
        }
    }
    
    /**
     * JDBC URL이 DADP URL 형식인지 확인
     */
    @Override
    public boolean acceptsURL(String url) throws SQLException {
        if (url == null) {
            return false;
        }
        return url.startsWith(DadpJdbcUrlSupport.DADP_URL_PREFIX);
    }
    
    /**
     * Connection 생성
     * DADP URL을 실제 DB URL로 변환하여 실제 Driver로 연결
     */
    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        
        try {
            // Connection Pool에서 반복적으로 생성되므로 TRACE 레벨로 처리 (로그 정책 참조)
            log.trace("DADP JDBC Driver connection request: {}", url);
            
            // JDBC URL에서 Proxy 설정 파라미터 추출 (hubUrl, instanceId, failOpen)
            java.util.Map<String, String> proxyParams = extractProxyParams(url);
            if (!proxyParams.isEmpty()) {
                // Connection Pool에서 반복적으로 생성되므로 TRACE 레벨로 처리 (로그 정책 참조)
                log.trace("Proxy config parameters extracted: {}", proxyParams);
            } else {
                log.warn("No proxy config parameters found. Using system properties or environment variables.");
            }
            
            // DADP URL을 실제 DB URL로 변환 (Proxy 파라미터 제거)
            String actualUrl = extractActualUrl(url);
            // Connection Pool에서 반복적으로 생성되므로 TRACE 레벨로 처리 (로그 정책 참조)
            log.trace("Actual DB URL: {}", actualUrl);
            
            // 실제 Driver로 연결
            Connection actualConnection;
            try {
                actualConnection = DriverManager.getConnection(actualUrl, info);
            } catch (SQLException e) {
                // 연결 실패 시 변환된 URL 정보를 로그에 출력 (디버깅용)
                if (e.getMessage() != null && e.getMessage().contains("too many")) {
                    log.warn("JDBC URL conversion error - original DADP URL: {}", url);
                    log.warn("Converted actual DB URL: {}", actualUrl);
                    log.warn("URL slash count: {}", countSlashes(actualUrl));
                    log.warn("Driver error message: {}", e.getMessage());
                }
                throw e;
            }
            
            // Proxy Connection으로 래핑 (Proxy 설정 + 접속 정보 전달)
            return new DadpProxyConnection(actualConnection, url, proxyParams, info);
            
        } catch (SQLException e) {
            log.warn("DADP JDBC Driver connection failed: {}", e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * JDBC URL에서 Proxy 설정 파라미터 추출
     * 예: jdbc:dadp:mysql://localhost:3306/db?hubUrl=http://192.168.0.21:9004&instanceId=sample-app-1
     * → {hubUrl: "http://192.168.0.21:9004", instanceId: "sample-app-1"}
     *
     * hubUrl is the Hub base URL without /hub.
     * Wrapper HTTP clients append /hub/api/... internally.
     */
    private java.util.Map<String, String> extractProxyParams(String dadpUrl) {
        return DadpJdbcUrlSupport.extractProxyParams(dadpUrl);
    }
    
    /**
     * DADP URL에서 실제 DB URL 추출 (Proxy 파라미터 제거)
     * 예: jdbc:dadp:mysql://localhost:3306/db?hubUrl=...&useSSL=false
     * → jdbc:mysql://localhost:3306/db?useSSL=false
     */
    private String extractActualUrl(String dadpUrl) {
        String actualUrl = DadpJdbcUrlSupport.extractActualUrl(dadpUrl);
        
        // 변환된 URL 검증: 슬래시 개수 체크 (디버깅용)
        int slashCount = countSlashes(actualUrl);
        if (slashCount > 5) { // jdbc:postgresql://host:port/db 형식은 최대 5개 (jdbc:, //, /)
            log.warn("Converted JDBC URL has too many slashes ({} slashes). URL: {}", slashCount, actualUrl);
            log.warn("Original DADP URL: {}", dadpUrl);
        }
        
        return actualUrl;
    }
    
    /**
     * URL에서 슬래시 개수 카운트 (디버깅용)
     */
    private int countSlashes(String url) {
        int count = 0;
        for (int i = 0; i < url.length(); i++) {
            if (url.charAt(i) == '/') {
                count++;
            }
        }
        return count;
    }
    
    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[0];
    }
    
    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }
    
    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }
    
    @Override
    public boolean jdbcCompliant() {
        return false; // JDBC 호환성 검증 우회
    }
    
    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("getParentLogger not supported");
    }
}
