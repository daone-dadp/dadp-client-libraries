package com.dadp.common.logging;

/**
 * DADP 공통 라이브러리 로깅 인터페이스
 * 
 * SLF4J와 완전히 분리된 추상 로깅 인터페이스로, 고객사 로깅 환경과 충돌을 방지합니다.
 * SLF4J가 있으면 자동으로 동작하고, 없으면 NOP로 작동합니다.
 * 
 * @author DADP Development Team
 */
public interface DadpLogger {
    void trace(String msg);
    void trace(String format, Object... args);
    void debug(String msg);
    void debug(String format, Object... args);
    void info(String msg);
    void info(String format, Object... args);
    void warn(String msg);
    void warn(String format, Object... args);
    void warn(String msg, Throwable t);
    void error(String msg);
    void error(String format, Object... args);
    void error(String msg, Throwable t);
    boolean isTraceEnabled();
    boolean isDebugEnabled();
    boolean isInfoEnabled();
    boolean isWarnEnabled();
    boolean isErrorEnabled();
}

