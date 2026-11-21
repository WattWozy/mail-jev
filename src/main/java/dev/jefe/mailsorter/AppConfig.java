package dev.jefe.mailsorter;

import java.net.URI;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

public record AppConfig(
        String webHost,
        int webPort,
        PersistenceMode persistence,
        Path dbPath,
        URI jevEndpoint,
        String jevApiKey
) {
    public enum PersistenceMode { MEMORY, SQLITE }

    public static AppConfig from(UnaryOperator<String> env) {
        return new AppConfig(
                orDefault(env, "WEB_HOST", "127.0.0.1"),
                Integer.parseInt(orDefault(env, "WEB_PORT", "8080")),
                PersistenceMode.valueOf(orDefault(env, "MAILSORTER_PERSISTENCE", "SQLITE").toUpperCase()),
                Path.of(orDefault(env, "MAILSORTER_DB_PATH", "./data/mailsorter.db")),
                URI.create(required(env, "JEV_ENDPOINT")),
                required(env, "JEV_API_KEY"));
    }

    private static String required(UnaryOperator<String> env, String key) {
        var v = env.apply(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing required setting: " + key);
        return v;
    }

    private static String orDefault(UnaryOperator<String> env, String key, String fallback) {
        var v = env.apply(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}