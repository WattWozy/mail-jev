package dev.jefe.mailsorter.infra.persistence.sqlite;

import dev.jefe.mailsorter.application.exception.PersistenceException;
import dev.jefe.mailsorter.application.port.out.AuditLog;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Classification;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record SqliteAuditLog(SqliteDatabase db) implements AuditLog {

    private static final String INSERT = """
    INSERT INTO audit_log(at, email_id, category, confidence, reason, action, dry_run, folder)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """;

    @Override
    public void record(String id, Classification c, Action a, boolean dryRun) {
        try (var con = db.connection(); var ps = con.prepareStatement(INSERT)) {
            ps.setString(1, Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
            ps.setString(2, id);
            ps.setString(3, c.category().name());
            ps.setDouble(4, c.confidence());
            ps.setString(5, c.reason());          // ← the missing one
            ps.setString(6, a.name());
            ps.setInt(7, dryRun ? 1 : 0);
            ps.setString(8, a == Action.MOVE_TO_FOLDER ? c.folder() : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            var ex = new PersistenceException("Could not write audit entry", e);
            AppLog.error(SqliteAuditLog.class, ex.getMessage(), ex);
            throw ex;
        }
    }
}