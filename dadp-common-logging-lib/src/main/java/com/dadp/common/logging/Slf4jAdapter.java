package com.dadp.common.logging;

import java.lang.reflect.Method;

/**
 * SLF4J Logger Adapter
 * 
 * 리플렉션을 사용하여 SLF4J Logger를 래핑합니다.
 * SLF4J가 클래스패스에 있을 때만 사용하며, 없으면 NoOpLogger로 폴백합니다.
 * 
 * @author DADP Development Team
 */
class Slf4jAdapter implements DadpLogger {
    private final Object slf4jLogger;
    private final Method traceMethod;
    private final Method debugMethod;
    private final Method infoMethod;
    private final Method warnMethod;
    private final Method warnWithThrowableMethod;
    private final Method errorMethod;
    private final Method errorWithThrowableMethod;
    private final Method isTraceEnabledMethod;
    private final Method isDebugEnabledMethod;
    private final Method isInfoEnabledMethod;
    private final Method isWarnEnabledMethod;
    private final Method isErrorEnabledMethod;
    
    Slf4jAdapter(String name) {
        try {
            Class<?> factoryClass = Class.forName("org.slf4j.LoggerFactory");
            Method getLoggerMethod = factoryClass.getMethod("getLogger", String.class);
            this.slf4jLogger = getLoggerMethod.invoke(null, name);
            Class<?> loggerClass = this.slf4jLogger.getClass();
            
            // 메서드 리플렉션으로 가져오기
            this.traceMethod = loggerClass.getMethod("trace", String.class);
            this.debugMethod = loggerClass.getMethod("debug", String.class);
            this.infoMethod = loggerClass.getMethod("info", String.class);
            this.warnMethod = loggerClass.getMethod("warn", String.class);
            this.warnWithThrowableMethod = loggerClass.getMethod("warn", String.class, Throwable.class);
            this.errorMethod = loggerClass.getMethod("error", String.class);
            this.errorWithThrowableMethod = loggerClass.getMethod("error", String.class, Throwable.class);
            this.isTraceEnabledMethod = loggerClass.getMethod("isTraceEnabled");
            this.isDebugEnabledMethod = loggerClass.getMethod("isDebugEnabled");
            this.isInfoEnabledMethod = loggerClass.getMethod("isInfoEnabled");
            this.isWarnEnabledMethod = loggerClass.getMethod("isWarnEnabled");
            this.isErrorEnabledMethod = loggerClass.getMethod("isErrorEnabled");
        } catch (Exception e) {
            // SLF4J 동작 체계가 깨진 경우 NoOp로 폴백
            // 생성 시에 실패하는 경우 DadpLoggerFactory에서 NoOp로 대체하도록 설계
            throw new RuntimeException("SLF4J Logger 생성 실패: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void trace(String msg) {
        try {
            traceMethod.invoke(slf4jLogger, msg);
        } catch (Exception e) {
            // 무시 (로깅 실패는 애플리케이션 작동에 영향 없음)
        }
    }
    
    @Override
    public void trace(String format, Object... args) {
        try {
            Class<?> loggerClass = slf4jLogger.getClass();
            Method method = loggerClass.getMethod("trace", String.class, Object[].class);
            method.invoke(slf4jLogger, format, args);
        } catch (Exception e) {
            // 무시
        }
    }
    
    @Override
    public void debug(String msg) {
        try {
            debugMethod.invoke(slf4jLogger, msg);
        } catch (Exception e) {
            // 무시
        }
    }
    
    @Override
    public void debug(String format, Object... args) {
        try {
            Class<?> loggerClass = slf4jLogger.getClass();
            Method method = loggerClass.getMethod("debug", String.class, Object[].class);
            method.invoke(slf4jLogger, format, args);
        } catch (Exception e) {
            // 무시
        }
    }
    
    @Override
    public void info(String msg) {
        try {
            infoMethod.invoke(slf4jLogger, msg);
        } catch (Exception e) {
            // 무시
        }
    }
    
    @Override
    public void info(String format, Object... args) {
        try {
            Class<?> loggerClass = slf4jLogger.getClass();
            Method method = loggerClass.getMethod("info", String.class, Object[].class);
            method.invoke(slf4jLogger, format, args);
        } catch (Exception e) {
            // 무시
        }
    }
    
    @Override
    public void warn(String msg) {
        try {
            warnMethod.invoke(slf4jLogger, msg);
        } catch (Exception e) {
            // 무시
        }
    }
    
    @Override
    public void warn(String format, Object... args) {
        try {
            Class<?> loggerClass = slf4jLogger.getClass();
            Method method = loggerClass.getMethod("warn", String.class, Object[].class);
            method.invoke(slf4jLogger, format, args);
        } catch (Exception e) {
            // 무시
        }
    }
    
    @Override
    public void warn(String msg, Throwable t) {
        try {
            warnWithThrowableMethod.invoke(slf4jLogger, msg, t);
        } catch (Exception e) {
            // 무시
        }
    }
    
    @Override
    public void error(String msg) {
        try {
            errorMethod.invoke(slf4jLogger, msg);
        } catch (Exception e) {
            // 무시
        }
    }
    
    @Override
    public void error(String format, Object... args) {
        try {
            Class<?> loggerClass = slf4jLogger.getClass();
            Method method = loggerClass.getMethod("error", String.class, Object[].class);
            method.invoke(slf4jLogger, format, args);
        } catch (Exception e) {
            // 무시
        }
    }
    
    @Override
    public void error(String msg, Throwable t) {
        try {
            errorWithThrowableMethod.invoke(slf4jLogger, msg, t);
        } catch (Exception e) {
            // 무시
        }
    }
    
    @Override
    public boolean isTraceEnabled() {
        try {
            return (Boolean) isTraceEnabledMethod.invoke(slf4jLogger);
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public boolean isDebugEnabled() {
        try {
            return (Boolean) isDebugEnabledMethod.invoke(slf4jLogger);
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public boolean isInfoEnabled() {
        try {
            return (Boolean) isInfoEnabledMethod.invoke(slf4jLogger);
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public boolean isWarnEnabled() {
        try {
            return (Boolean) isWarnEnabledMethod.invoke(slf4jLogger);
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public boolean isErrorEnabled() {
        try {
            return (Boolean) isErrorEnabledMethod.invoke(slf4jLogger);
        } catch (Exception e) {
            return false;
        }
    }
}

