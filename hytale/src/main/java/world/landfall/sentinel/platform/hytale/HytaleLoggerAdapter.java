package world.landfall.sentinel.platform.hytale;

import org.slf4j.Logger;
import org.slf4j.Marker;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Adapter that wraps Hytale's logger to implement SLF4J's Logger interface.
 * Uses reflection since HytaleLogger may not be directly accessible at compile time.
 */
public class HytaleLoggerAdapter implements Logger {

    private final Object hytaleLogger;
    private final String name;
    private Method atMethod;
    private boolean methodsResolved;

    public HytaleLoggerAdapter(Object hytaleLogger) {
        this.hytaleLogger = hytaleLogger;
        this.name = "Sentinel";
        this.methodsResolved = false;
    }

    private void ensureMethodsResolved() {
        if (!methodsResolved) {
            methodsResolved = true;
            try {
                atMethod = hytaleLogger.getClass().getMethod("at", Level.class);
            } catch (Exception e) {
                // Fall through to null check
            }
        }
    }

    private void logAtLevel(Level level, String message) {
        ensureMethodsResolved();
        if (atMethod != null) {
            try {
                Object logApi = atMethod.invoke(hytaleLogger, level);
                Method logMethod = logApi.getClass().getMethod("log", String.class);
                logMethod.invoke(logApi, message);
            } catch (Exception e) {
                System.err.println("[" + level.getName() + "] " + message);
            }
        } else {
            System.out.println("[" + level.getName() + "] " + message);
        }
    }

    private void logAtLevelWithThrowable(Level level, String message, Throwable t) {
        ensureMethodsResolved();
        if (atMethod != null) {
            try {
                Object logApi = atMethod.invoke(hytaleLogger, level);
                Method withCauseMethod = logApi.getClass().getMethod("withCause", Throwable.class);
                Object logApiWithCause = withCauseMethod.invoke(logApi, t);
                Method logMethod = logApiWithCause.getClass().getMethod("log", String.class);
                logMethod.invoke(logApiWithCause, message);
            } catch (Exception e) {
                System.err.println("[" + level.getName() + "] " + message);
                t.printStackTrace();
            }
        } else {
            System.out.println("[" + level.getName() + "] " + message);
            t.printStackTrace();
        }
    }

    @Override
    public String getName() {
        return name;
    }

    private String format(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        while (i < message.length()) {
            if (i < message.length() - 1 && message.charAt(i) == '{' && message.charAt(i + 1) == '}') {
                if (argIndex < args.length) {
                    sb.append(args[argIndex++]);
                } else {
                    sb.append("{}");
                }
                i += 2;
            } else {
                sb.append(message.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    // TRACE
    @Override public boolean isTraceEnabled() { return true; }
    @Override public void trace(String msg) { logAtLevel(Level.FINEST, msg); }
    @Override public void trace(String format, Object arg) { logAtLevel(Level.FINEST, format(format, arg)); }
    @Override public void trace(String format, Object arg1, Object arg2) { logAtLevel(Level.FINEST, format(format, arg1, arg2)); }
    @Override public void trace(String format, Object... arguments) { logAtLevel(Level.FINEST, format(format, arguments)); }
    @Override public void trace(String msg, Throwable t) { logAtLevelWithThrowable(Level.FINEST, msg, t); }
    @Override public boolean isTraceEnabled(Marker marker) { return isTraceEnabled(); }
    @Override public void trace(Marker marker, String msg) { trace(msg); }
    @Override public void trace(Marker marker, String format, Object arg) { trace(format, arg); }
    @Override public void trace(Marker marker, String format, Object arg1, Object arg2) { trace(format, arg1, arg2); }
    @Override public void trace(Marker marker, String format, Object... argArray) { trace(format, argArray); }
    @Override public void trace(Marker marker, String msg, Throwable t) { trace(msg, t); }

    // DEBUG
    @Override public boolean isDebugEnabled() { return true; }
    @Override public void debug(String msg) { logAtLevel(Level.FINE, msg); }
    @Override public void debug(String format, Object arg) { logAtLevel(Level.FINE, format(format, arg)); }
    @Override public void debug(String format, Object arg1, Object arg2) { logAtLevel(Level.FINE, format(format, arg1, arg2)); }
    @Override public void debug(String format, Object... arguments) { logAtLevel(Level.FINE, format(format, arguments)); }
    @Override public void debug(String msg, Throwable t) { logAtLevelWithThrowable(Level.FINE, msg, t); }
    @Override public boolean isDebugEnabled(Marker marker) { return isDebugEnabled(); }
    @Override public void debug(Marker marker, String msg) { debug(msg); }
    @Override public void debug(Marker marker, String format, Object arg) { debug(format, arg); }
    @Override public void debug(Marker marker, String format, Object arg1, Object arg2) { debug(format, arg1, arg2); }
    @Override public void debug(Marker marker, String format, Object... arguments) { debug(format, arguments); }
    @Override public void debug(Marker marker, String msg, Throwable t) { debug(msg, t); }

    // INFO
    @Override public boolean isInfoEnabled() { return true; }
    @Override public void info(String msg) { logAtLevel(Level.INFO, msg); }
    @Override public void info(String format, Object arg) { logAtLevel(Level.INFO, format(format, arg)); }
    @Override public void info(String format, Object arg1, Object arg2) { logAtLevel(Level.INFO, format(format, arg1, arg2)); }
    @Override public void info(String format, Object... arguments) { logAtLevel(Level.INFO, format(format, arguments)); }
    @Override public void info(String msg, Throwable t) { logAtLevelWithThrowable(Level.INFO, msg, t); }
    @Override public boolean isInfoEnabled(Marker marker) { return isInfoEnabled(); }
    @Override public void info(Marker marker, String msg) { info(msg); }
    @Override public void info(Marker marker, String format, Object arg) { info(format, arg); }
    @Override public void info(Marker marker, String format, Object arg1, Object arg2) { info(format, arg1, arg2); }
    @Override public void info(Marker marker, String format, Object... arguments) { info(format, arguments); }
    @Override public void info(Marker marker, String msg, Throwable t) { info(msg, t); }

    // WARN
    @Override public boolean isWarnEnabled() { return true; }
    @Override public void warn(String msg) { logAtLevel(Level.WARNING, msg); }
    @Override public void warn(String format, Object arg) { logAtLevel(Level.WARNING, format(format, arg)); }
    @Override public void warn(String format, Object... arguments) { logAtLevel(Level.WARNING, format(format, arguments)); }
    @Override public void warn(String format, Object arg1, Object arg2) { logAtLevel(Level.WARNING, format(format, arg1, arg2)); }
    @Override public void warn(String msg, Throwable t) { logAtLevelWithThrowable(Level.WARNING, msg, t); }
    @Override public boolean isWarnEnabled(Marker marker) { return isWarnEnabled(); }
    @Override public void warn(Marker marker, String msg) { warn(msg); }
    @Override public void warn(Marker marker, String format, Object arg) { warn(format, arg); }
    @Override public void warn(Marker marker, String format, Object arg1, Object arg2) { warn(format, arg1, arg2); }
    @Override public void warn(Marker marker, String format, Object... arguments) { warn(format, arguments); }
    @Override public void warn(Marker marker, String msg, Throwable t) { warn(msg, t); }

    // ERROR
    @Override public boolean isErrorEnabled() { return true; }
    @Override public void error(String msg) { logAtLevel(Level.SEVERE, msg); }
    @Override public void error(String format, Object arg) { logAtLevel(Level.SEVERE, format(format, arg)); }
    @Override public void error(String format, Object arg1, Object arg2) { logAtLevel(Level.SEVERE, format(format, arg1, arg2)); }
    @Override public void error(String format, Object... arguments) { logAtLevel(Level.SEVERE, format(format, arguments)); }
    @Override public void error(String msg, Throwable t) { logAtLevelWithThrowable(Level.SEVERE, msg, t); }
    @Override public boolean isErrorEnabled(Marker marker) { return isErrorEnabled(); }
    @Override public void error(Marker marker, String msg) { error(msg); }
    @Override public void error(Marker marker, String format, Object arg) { error(format, arg); }
    @Override public void error(Marker marker, String format, Object arg1, Object arg2) { error(format, arg1, arg2); }
    @Override public void error(Marker marker, String format, Object... arguments) { error(format, arguments); }
    @Override public void error(Marker marker, String msg, Throwable t) { error(msg, t); }
}
