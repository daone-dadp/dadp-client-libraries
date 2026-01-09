package com.dadp.jdbc.logging;

/**
 * NOP (No Operation) Logger 구현
 * 
 * SLF4J가 없는 환경에서 사용되는 로거로, 모든 로깅 호출을 무시합니다.
 * 
 * @author DADP Development Team
 */
class NoOpLogger implements DadpLogger {
    static final NoOpLogger INSTANCE = new NoOpLogger();
    
    private NoOpLogger() {}
    
    @Override
    public void trace(String msg) {}
    
    @Override
    public void trace(String format, Object... args) {}
    
    @Override
    public void debug(String msg) {}
    
    @Override
    public void debug(String format, Object... args) {}
    
    @Override
    public void info(String msg) {}
    
    @Override
    public void info(String format, Object... args) {}
    
    @Override
    public void warn(String msg) {}
    
    @Override
    public void warn(String format, Object... args) {}
    
    @Override
    public void warn(String msg, Throwable t) {}
    
    @Override
    public void error(String msg) {}
    
    @Override
    public void error(String format, Object... args) {}
    
    @Override
    public void error(String msg, Throwable t) {}
    
    @Override
    public boolean isTraceEnabled() { return false; }
    
    @Override
    public boolean isDebugEnabled() { return false; }
    
    @Override
    public boolean isInfoEnabled() { return false; }
    
    @Override
    public boolean isWarnEnabled() { return false; }
    
    @Override
    public boolean isErrorEnabled() { return false; }
}

