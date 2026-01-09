package com.dadp.jdbc.logging;

/**
 * DADP JDBC Wrapper 전용 로깅 인터페이스
 * 
 * SLF4J와 완전히 분리된 자체 로깅 추상화로, 고객사 로깅 환경과 충돌하지 않습니다.
 * SLF4J가 있으면 자동으로 연동하고, 없으면 NOP으로 동작합니다.
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

