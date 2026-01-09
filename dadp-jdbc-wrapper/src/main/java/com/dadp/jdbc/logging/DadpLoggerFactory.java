package com.dadp.jdbc.logging;

/**
 * DADP Logger Factory
 * 
 * 환경 변수 DADP_WRAPPER_ENABLE_LOGGING이 true일 때만 로깅을 활성화합니다.
 * 기본값: false (환경 변수로 true를 설정해야 로그 출력)
 * 
 * SLF4J가 클래스패스에 있는지 확인하고, 있으면 Slf4jAdapter를 사용하고
 * 없으면 NoOpLogger를 반환합니다.
 * 
 * @author DADP Development Team
 */
public final class DadpLoggerFactory {
    private static final boolean slf4jAvailable;
    private static final boolean loggingEnabled;
    
    static {
        boolean available = false;
        try {
            Class.forName("org.slf4j.LoggerFactory");
            available = true;
        } catch (ClassNotFoundException e) {
            available = false;
        }
        slf4jAvailable = available;
        
        // 환경 변수 확인: DADP_WRAPPER_ENABLE_LOGGING 또는 DADP_PROXY_ENABLE_LOGGING
        String enableLogging = System.getenv("DADP_WRAPPER_ENABLE_LOGGING");
        if (enableLogging == null || enableLogging.trim().isEmpty()) {
            enableLogging = System.getenv("DADP_PROXY_ENABLE_LOGGING");
        }
        // 시스템 프로퍼티도 확인
        if (enableLogging == null || enableLogging.trim().isEmpty()) {
            enableLogging = System.getProperty("dadp.wrapper.enable-logging");
        }
        if (enableLogging == null || enableLogging.trim().isEmpty()) {
            enableLogging = System.getProperty("dadp.proxy.enable-logging");
        }
        // 기본값: false (환경 변수로 true를 설정해야 로그 출력)
        loggingEnabled = "true".equalsIgnoreCase(enableLogging) || "1".equals(enableLogging);
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
     * @param name 로거 이름
     * @return DadpLogger 인스턴스
     */
    public static DadpLogger getLogger(String name) {
        // 환경 변수가 true가 아니면 항상 NoOp 반환
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
}

