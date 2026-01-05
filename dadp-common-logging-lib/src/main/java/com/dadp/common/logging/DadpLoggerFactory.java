package com.dadp.common.logging;

/**
 * DADP Logger Factory
 * 
 * SLF4J가 클래스패스에 있는지 확인하고, 있으면 Slf4jAdapter를 사용하고
 * 없으면 NoOpLogger를 반환합니다.
 * 
 * @author DADP Development Team
 */
public final class DadpLoggerFactory {
    private static final boolean slf4jAvailable;
    
    static {
        boolean available = false;
        try {
            Class.forName("org.slf4j.LoggerFactory");
            available = true;
        } catch (ClassNotFoundException e) {
            available = false;
        }
        slf4jAvailable = available;
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
        if (slf4jAvailable) {
            try {
                return new Slf4jAdapter(name);
            } catch (RuntimeException e) {
                // SLF4J 동작 실패 시 NoOp로 폴백
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

