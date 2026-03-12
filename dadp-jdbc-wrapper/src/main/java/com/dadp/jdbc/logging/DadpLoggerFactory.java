package com.dadp.jdbc.logging;

/**
 * DADP Logger Factory
 *
 * 로그 출력 조건:
 * - DADP_ENABLE_LOGGING = true 또는 dadp.enable-logging = true
 *
 * 기본값: false (로그 출력 안 함)
 * 고객사 앱에서 명시적으로 설정해야만 로그가 출력됩니다.
 *
 * 설정 방법 (우선순위):
 * 1. 환경 변수: DADP_ENABLE_LOGGING=true
 * 2. 시스템 프로퍼티: -Ddadp.enable-logging=true
 * 3. JDBC URL 파라미터: enableLogging=true (ProxyConfig를 통해 전달)
 * 4. Hub PolicySnapshot logConfig (동적 변경)
 *
 * SLF4J가 있으면 SLF4J를 통해 출력하고, 없으면 System.out 폴백 로거를 사용합니다.
 *
 * @author DADP Development Team
 */
public final class DadpLoggerFactory {

    // 로그 레벨 순서 (낮을수록 상세)
    private static final String[] LOG_LEVEL_ORDER = { "TRACE", "DEBUG", "INFO", "WARN", "ERROR" };

    private static final boolean slf4jAvailable;
    private static volatile boolean loggingEnabled;

    /** Hub PolicySnapshot logConfig 로 동적으로 설정되는 최소 로그 레벨 (기본값: INFO) */
    private static volatile String minimumLogLevel = "INFO";
    
    static {
        // SLF4J 사용 가능 여부 확인 (있으면 사용, 없으면 NoOpLogger 사용)
        boolean available = false;
        try {
            Class.forName("org.slf4j.LoggerFactory");
            available = true;
        } catch (ClassNotFoundException e) {
            available = false;
        }
        slf4jAvailable = available;
        
        // DADP 통합 로그 활성화 설정 확인
        // 환경 변수 우선 확인: DADP_ENABLE_LOGGING
        String enableLogging = System.getenv("DADP_ENABLE_LOGGING");
        if (enableLogging == null || enableLogging.trim().isEmpty()) {
            // 시스템 프로퍼티 확인: dadp.enable-logging
            enableLogging = System.getProperty("dadp.enable-logging");
        }
        // 기본값: false (설정하지 않으면 로그 출력 안 함)
        // true 또는 "1"로 설정해야만 로그 출력
        loggingEnabled = "true".equalsIgnoreCase(enableLogging) || "1".equals(enableLogging);
    }
    
    /**
     * 로그 활성화 설정을 동적으로 변경합니다.
     * ProxyConfig에서 JDBC URL 파라미터를 읽은 후 호출할 수 있습니다.
     * Hub PolicySnapshot logConfig 수신 시에도 호출됩니다.
     *
     * @param enabled 로그 활성화 여부
     */
    public static void setLoggingEnabled(boolean enabled) {
        loggingEnabled = enabled;
    }

    /**
     * 최소 로그 레벨을 동적으로 설정합니다.
     * Hub PolicySnapshot logConfig 수신 시 호출됩니다.
     * 유효하지 않은 값이 전달되면 INFO로 fallback 합니다.
     *
     * @param level 로그 레벨 문자열 (TRACE, DEBUG, INFO, WARN, ERROR)
     */
    public static void setLogLevel(String level) {
        if (level == null || level.trim().isEmpty()) {
            minimumLogLevel = "INFO";
            return;
        }
        String upper = level.trim().toUpperCase();
        for (String l : LOG_LEVEL_ORDER) {
            if (l.equals(upper)) {
                minimumLogLevel = upper;
                return;
            }
        }
        // 알 수 없는 레벨은 INFO로 fallback
        minimumLogLevel = "INFO";
    }

    /**
     * 현재 설정된 최소 로그 레벨을 반환합니다.
     *
     * @return 최소 로그 레벨 문자열 (TRACE, DEBUG, INFO, WARN, ERROR)
     */
    public static String getLogLevel() {
        return minimumLogLevel;
    }

    /**
     * 주어진 레벨이 현재 최소 로그 레벨 이상인지 확인합니다.
     * 예: minimumLogLevel=INFO이면 DEBUG/TRACE는 false, INFO/WARN/ERROR는 true.
     *
     * @param level 확인할 로그 레벨 (TRACE, DEBUG, INFO, WARN, ERROR)
     * @return 해당 레벨에서 로그를 출력해야 하면 true
     */
    public static boolean isLevelEnabled(String level) {
        if (level == null) {
            return false;
        }
        String upper = level.trim().toUpperCase();
        int requestedOrdinal = -1;
        int minimumOrdinal = -1;
        for (int i = 0; i < LOG_LEVEL_ORDER.length; i++) {
            if (LOG_LEVEL_ORDER[i].equals(upper)) {
                requestedOrdinal = i;
            }
            if (LOG_LEVEL_ORDER[i].equals(minimumLogLevel)) {
                minimumOrdinal = i;
            }
        }
        if (requestedOrdinal < 0 || minimumOrdinal < 0) {
            // 알 수 없는 레벨은 허용
            return true;
        }
        return requestedOrdinal >= minimumOrdinal;
    }
    
    /**
     * 클래스 기반으로 Logger를 생성합니다.
     * 
     * @param clazz 로거를 생성할 클래스
     * @return DadpLogger 인스턴스
     */
    public static DadpLogger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }
    
    /**
     * 이름 기반으로 Logger를 생성합니다.
     * 
     * DADP_ENABLE_LOGGING = true이면 로그를 출력합니다.
     * SLF4J가 있으면 Slf4jAdapter를 반환하고, 없으면 NoOpLogger를 반환합니다.
     * 
     * @param name 로거 이름
     * @return DadpLogger 인스턴스
     */
    public static DadpLogger getLogger(String name) {
        // DADP_ENABLE_LOGGING이 true가 아니면 NoOpLogger 반환
        if (!loggingEnabled) {
            return NoOpLogger.INSTANCE;
        }
        
        // SLF4J가 있으면 Slf4jAdapter 사용, 없으면 ConsoleLogger(System.out) 폴백
        if (slf4jAvailable) {
            try {
                return new Slf4jAdapter(name);
            } catch (RuntimeException e) {
                // SLF4J 연동 실패 시 ConsoleLogger 폴백
                return new ConsoleLogger(name);
            }
        } else {
            // SLF4J가 없으면 ConsoleLogger (System.out → catalina.out 등)
            return new ConsoleLogger(name);
        }
    }
    
    /**
     * SLF4J가 사용 가능한지 확인합니다.
     * 
     * @return SLF4J 사용 가능 여부
     */
    public static boolean isSlf4jAvailable() {
        return slf4jAvailable;
    }
    
    /**
     * DADP 로그 활성화 설정이 true인지 확인합니다.
     * 
     * @return 로그 활성화 여부
     */
    public static boolean isLoggingEnabled() {
        return loggingEnabled;
    }
    
    /**
     * 로그가 실제로 출력될 수 있는지 확인합니다.
     * 
     * DADP_ENABLE_LOGGING = true AND SLF4J가 있을 때만 true를 반환합니다.
     * 
     * @return 로그 출력 가능 여부
     */
    public static boolean isLoggingAvailable() {
        return slf4jAvailable && loggingEnabled;
    }
}

