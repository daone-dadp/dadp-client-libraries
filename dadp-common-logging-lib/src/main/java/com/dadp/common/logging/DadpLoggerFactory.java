package com.dadp.common.logging;

/**
 * DADP Logger Factory (Common)
 * 
 * DADP_ENABLE_LOGGING = true일 때만 로깅을 활성화합니다.
 * 기본값: false (환경 변수로 true를 설정해야 로그 출력)
 * 
 * 설정 방법 (우선순위):
 * 1. 환경 변수: DADP_ENABLE_LOGGING=true
 * 2. 시스템 프로퍼티: -Ddadp.enable-logging=true
 * 
 * SLF4J가 클래스패스에 있는지 확인하고, 있으면 Slf4jAdapter를 사용하고
 * 없으면 NoOpLogger를 반환합니다.
 * 
 * @author DADP Development Team
 */
public final class DadpLoggerFactory {
    private static final boolean slf4jAvailable;
    private static volatile boolean loggingEnabled;
    
    static {
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
     * 
     * @param enabled 로그 활성화 여부
     */
    public static void setLoggingEnabled(boolean enabled) {
        loggingEnabled = enabled;
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
        
        if (slf4jAvailable) {
            try {
                return new Slf4jAdapter(name);
            } catch (RuntimeException e) {
                // SLF4J 연동 실패 시 NoOp로 폴백
                return NoOpLogger.INSTANCE;
            }
        } else {
            return NoOpLogger.INSTANCE;
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

