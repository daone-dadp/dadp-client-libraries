package com.dadp.jdbc.logging;

/**
 * System.out 기반 폴백 로거
 *
 * SLF4J가 없는 환경(standalone Tomcat 등)에서 enableLogging=true 시 사용.
 * catalina.out 등 표준 출력으로 로그를 출력합니다.
 */
final class ConsoleLogger implements DadpLogger {
    private final String name;

    ConsoleLogger(String name) {
        // 패키지 경로 축약: com.dadp.jdbc.sync.JdbcBootstrapOrchestrator → c.d.j.s.JdbcBootstrapOrchestrator
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            String className = name.substring(lastDot + 1);
            String pkg = name.substring(0, lastDot);
            StringBuilder sb = new StringBuilder();
            for (String part : pkg.split("\\.")) {
                if (sb.length() > 0) sb.append('.');
                sb.append(part.charAt(0));
            }
            this.name = sb + "." + className;
        } else {
            this.name = name;
        }
    }

    private void log(String level, String msg) {
        System.out.println("[DADP-" + level + "] " + name + " - " + msg);
    }

    private void log(String level, String format, Object... args) {
        String msg = format;
        if (args != null) {
            for (Object arg : args) {
                int idx = msg.indexOf("{}");
                if (idx >= 0) {
                    msg = msg.substring(0, idx) + arg + msg.substring(idx + 2);
                } else {
                    break;
                }
            }
        }
        log(level, msg);
    }

    @Override public void trace(String msg) { log("TRACE", msg); }
    @Override public void trace(String format, Object... args) { log("TRACE", format, args); }
    @Override public void debug(String msg) { log("DEBUG", msg); }
    @Override public void debug(String format, Object... args) { log("DEBUG", format, args); }
    @Override public void info(String msg) { log("INFO", msg); }
    @Override public void info(String format, Object... args) { log("INFO", format, args); }
    @Override public void warn(String msg) { log("WARN", msg); }
    @Override public void warn(String format, Object... args) { log("WARN", format, args); }
    @Override public void warn(String msg, Throwable t) { log("WARN", msg + " - " + t.getMessage()); }
    @Override public void error(String msg) { log("ERROR", msg); }
    @Override public void error(String format, Object... args) { log("ERROR", format, args); }
    @Override public void error(String msg, Throwable t) { log("ERROR", msg + " - " + t.getMessage()); }
    @Override public boolean isTraceEnabled() { return true; }
    @Override public boolean isDebugEnabled() { return true; }
    @Override public boolean isInfoEnabled() { return true; }
    @Override public boolean isWarnEnabled() { return true; }
    @Override public boolean isErrorEnabled() { return true; }
}
