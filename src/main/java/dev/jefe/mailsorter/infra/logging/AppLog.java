package dev.jefe.mailsorter.infra.logging;

import org.slf4j.LoggerFactory;

public final class AppLog {
    private AppLog() {}

    public static void warn(Class<?> origin, String msg, Object... args) {
        LoggerFactory.getLogger(origin).warn(msg, args);
    }

    public static void error(Class<?> origin, String msg, Throwable t) {
        LoggerFactory.getLogger(origin).error(msg, t);
    }
}
