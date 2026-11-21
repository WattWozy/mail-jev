package dev.jefe.mailsorter.infra.sqlite;

import dev.jefe.mailsorter.application.exception.PersistenceException;
import dev.jefe.mailsorter.infra.logging.AppLog;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class SqliteDatabase {
    private final String url;

    public SqliteDatabase(Path file) {
        try {
            var abs = file.toAbsolutePath();
            Files.createDirectories(abs.getParent());
            this.url = "jdbc:sqlite:" + abs;
        } catch (IOException e) {
            var ex = new PersistenceException("Cannot create database directory");
            AppLog.error(SqliteDatabase.class, ex.getMessage(), ex);
            throw ex;
        }
    }

    public Connection connection() throws SQLException {
        var cfg = new SQLiteConfig();
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
        cfg.setBusyTimeout(5_000);      // wait instead of failing on a lock
        cfg.enforceForeignKeys(true);
        return DriverManager.getConnection(url, cfg.toProperties());
    }

    public void migrate() {
        Flyway.configure().dataSource(url, null, null).load().migrate();
    }
}