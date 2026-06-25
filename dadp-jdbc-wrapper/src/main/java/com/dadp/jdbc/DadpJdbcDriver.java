package com.dadp.jdbc;

import com.dadp.jdbc.logging.DadpLogger;
import com.dadp.jdbc.logging.DadpLoggerFactory;
import com.dadp.jdbc.config.ProxyConfig;
import com.dadp.jdbc.notification.HubNotificationService;
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
            
            DadpJdbcUrlSupport.validateNoDadpRuntimeParams(url);
            java.util.Map<String, String> proxyParams = java.util.Collections.emptyMap();
            
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
                if (isDatabaseConnectionLimitError(e)) {
                    log.warn("JDBC URL conversion error - original DADP URL: {}", url);
                    log.warn("Converted actual DB URL: {}", actualUrl);
                    log.warn("URL slash count: {}", countSlashes(actualUrl));
                    log.warn("Driver error message: {}", e.getMessage());
                    notifyDatabaseConnectionFailure(actualUrl, e);
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
     * DADP 6 JDBC URL does not carry wrapper runtime parameters.
     */
    private java.util.Map<String, String> extractProxyParams(String dadpUrl) {
        return DadpJdbcUrlSupport.extractProxyParams(dadpUrl);
    }
    
    /**
     * DADP URL에서 실제 DB URL 추출
     * 예: jdbc:dadp:mysql://localhost:3306/db?useSSL=false
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

    private boolean isDatabaseConnectionLimitError(SQLException e) {
        String message = e != null ? e.getMessage() : null;
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("too many clients")
                || lower.contains("remaining connection slots")
                || lower.contains("too many connections");
    }

    private void notifyDatabaseConnectionFailure(String actualUrl, SQLException e) {
        try {
            ProxyConfig.NotificationContext context = ProxyConfig.loadNotificationContext();
            if (context == null) {
                return;
            }
            HubNotificationService service = new HubNotificationService(
                    context.getHubUrl(),
                    context.getTenantId(),
                    context.getAlias(),
                    null);
            service.notifyDatabaseConnectionFailure(
                    "DB_CONNECTION_LIMIT",
                    e != null ? e.getMessage() : null,
                    actualUrl);
        } catch (Exception notifyError) {
            log.debug("DB connection failure notification skipped: {}", notifyError.getMessage());
        }
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
