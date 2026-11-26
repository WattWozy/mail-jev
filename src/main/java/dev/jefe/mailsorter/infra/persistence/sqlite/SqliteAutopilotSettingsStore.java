package dev.jefe.mailsorter.infra.persistence.sqlite;

import dev.jefe.mailsorter.application.exception.PersistenceException;
import dev.jefe.mailsorter.application.port.out.AutopilotSettingsStore;
import dev.jefe.mailsorter.application.port.out.dto.AutopilotSettings;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

public record SqliteAutopilotSettingsStore(SqliteDatabase db) implements AutopilotSettingsStore {

    private static final String SELECT = """
            SELECT enabled, interval_seconds, batch_limit, account_id
            FROM autopilot_settings
            WHERE id = 1
            """;

    private static final String UPSERT = """
            INSERT INTO autopilot_settings(id, enabled, interval_seconds, batch_limit, account_id, updated_at)
            VALUES (1, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                enabled          = excluded.enabled,
                interval_seconds = excluded.interval_seconds,
                batch_limit      = excluded.batch_limit,
                account_id       = excluded.account_id,
                updated_at       = excluded.updated_at
            """;

    public SqliteAutopilotSettingsStore {
        Objects.requireNonNull(db, "db is required");
    }

    @Override
    public Optional<AutopilotSettings> load() {
        try (var con = db.connection();
             var ps = con.prepareStatement(SELECT);
             var rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new AutopilotSettings(
                    rs.getInt("enabled") == 1,
                    rs.getInt("interval_seconds"),
                    rs.getInt("batch_limit"),
                    rs.getString("account_id")));
        } catch (SQLException e) {
            var ex = new PersistenceException("Could not load autopilot settings", e);
            AppLog.error(SqliteAutopilotSettingsStore.class, ex.getMessage(), ex);
            throw ex;
        }
    }

    @Override
    public void save(AutopilotSettings settings) {
        Objects.requireNonNull(settings, "settings is required");
        try (var con = db.connection();
             var ps = con.prepareStatement(UPSERT)) {
            ps.setInt(1, settings.enabled() ? 1 : 0);
            ps.setInt(2, settings.intervalSeconds());
            ps.setInt(3, settings.limit());
            ps.setString(4, settings.accountId());
            ps.setString(5, Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            var ex = new PersistenceException("Could not save autopilot settings", e);
            AppLog.error(SqliteAutopilotSettingsStore.class, ex.getMessage(), ex);
            throw ex;
        }
    }
}
