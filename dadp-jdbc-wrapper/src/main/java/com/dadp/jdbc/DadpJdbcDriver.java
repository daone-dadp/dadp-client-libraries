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
    
    private static final String DADP_URL_PREFIX = "jdbc:dadp:";
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
        return url.startsWith(DADP_URL_PREFIX);
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
     * 예: jdbc:dadp:mysql://localhost:3306/db?hubUrl=http://localhost:9004&instanceId=sample-app-1
     * → {hubUrl: "http://localhost:9004", instanceId: "sample-app-1"}
     */
    private java.util.Map<String, String> extractProxyParams(String dadpUrl) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        
        // ? 또는 &로 시작하는 쿼리 파라미터 처리
        int queryIndex = dadpUrl.indexOf('?');
        int ampIndex = dadpUrl.indexOf('&');
        
        // ? 또는 & 중 먼저 나오는 것을 쿼리 시작점으로 사용
        int paramStartIndex = -1;
        if (queryIndex != -1 && ampIndex != -1) {
            paramStartIndex = Math.min(queryIndex, ampIndex);
        } else if (queryIndex != -1) {
            paramStartIndex = queryIndex;
        } else if (ampIndex != -1) {
            paramStartIndex = ampIndex;
        }
        
        if (paramStartIndex == -1) {
            return params; // 쿼리 파라미터 없음
        }
        
        String queryString = dadpUrl.substring(paramStartIndex + 1);
        String[] pairs = queryString.split("&");
        
        for (String pair : pairs) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex > 0) {
                String key = pair.substring(0, eqIndex).trim();
                String value = pair.substring(eqIndex + 1).trim();
                
                // Proxy 설정 파라미터만 추출
                if ("hubUrl".equals(key) || "instanceId".equals(key) || "failOpen".equals(key) || "enableLogging".equals(key)) {
                    try {
                        // URL 디코딩
                        value = java.net.URLDecoder.decode(value, "UTF-8");
                    } catch (java.io.UnsupportedEncodingException e) {
                        // UTF-8은 항상 지원되므로 발생하지 않음
                    }
                    params.put(key, value);
                }
            }
        }
        
        return params;
    }
    
    /**
     * DADP URL에서 실제 DB URL 추출 (Proxy 파라미터 제거)
     * 예: jdbc:dadp:mysql://localhost:3306/db?hubUrl=...&useSSL=false
     * → jdbc:mysql://localhost:3306/db?useSSL=false
     */
    private String extractActualUrl(String dadpUrl) {
        if (!dadpUrl.startsWith(DADP_URL_PREFIX)) {
            throw new IllegalArgumentException("Invalid DADP URL: " + dadpUrl);
        }
        
        // jdbc:dadp: 제거
        String urlWithoutPrefix = dadpUrl.substring(DADP_URL_PREFIX.length());
        
        // Proxy 파라미터 제거 (hubUrl, instanceId, failOpen)
        // ? 또는 &로 시작하는 쿼리 파라미터 처리
        int queryIndex = urlWithoutPrefix.indexOf('?');
        int ampIndex = urlWithoutPrefix.indexOf('&');
        
        // ? 또는 & 중 먼저 나오는 것을 쿼리 시작점으로 사용
        int paramStartIndex = -1;
        if (queryIndex != -1 && ampIndex != -1) {
            paramStartIndex = Math.min(queryIndex, ampIndex);
        } else if (queryIndex != -1) {
            paramStartIndex = queryIndex;
        } else if (ampIndex != -1) {
            paramStartIndex = ampIndex;
        }
        
        if (paramStartIndex != -1) {
            String baseUrl = urlWithoutPrefix.substring(0, paramStartIndex);
            String queryString = urlWithoutPrefix.substring(paramStartIndex + 1);
            
            // Proxy 파라미터를 제외한 쿼리 파라미터만 유지
            java.util.List<String> validParams = new java.util.ArrayList<>();
            String[] pairs = queryString.split("&");
            
            for (String pair : pairs) {
                int eqIndex = pair.indexOf('=');
                if (eqIndex > 0) {
                    String key = pair.substring(0, eqIndex).trim();
                    // Proxy 파라미터가 아니면 유지
                    if (!"hubUrl".equals(key) && !"instanceId".equals(key) && !"failOpen".equals(key) && !"enableLogging".equals(key)) {
                        validParams.add(pair);
                    }
                } else {
                    // 키=값 형식이 아닌 경우도 유지
                    validParams.add(pair);
                }
            }
            
            // 유효한 파라미터가 있으면 재구성
            if (!validParams.isEmpty()) {
                urlWithoutPrefix = baseUrl + "?" + String.join("&", validParams);
            } else {
                urlWithoutPrefix = baseUrl;
            }
        }
        
        // jdbc: 접두사 추가
        String actualUrl = "jdbc:" + urlWithoutPrefix;
        
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

