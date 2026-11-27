package dev.jefe.mailsorter.infra.persistence.sqlite;

import dev.jefe.mailsorter.application.exception.PersistenceException;
import dev.jefe.mailsorter.application.port.out.ActivityRepository;
import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.StatsSnapshot;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.ClassificationSource;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record SqliteActivityRepository(SqliteDatabase db) implements ActivityRepository {

    private static final String INSERT = """
            INSERT INTO activity(at, account_id, email_id, sender, subject, category, confidence,
                                 reason, action, source, overridden, folder)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String RECENT = """
            SELECT at, account_id, email_id, sender, subject, category, confidence,
                   reason, action, source, overridden, folder
            FROM activity
            WHERE (? IS NULL OR account_id = ?)
            ORDER BY at DESC, id DESC
            LIMIT ?
            """;

    private static final String STATS = """
            SELECT COUNT(*)                                                 AS total,
                   COALESCE(SUM(CASE WHEN source = 'RULE'  THEN 1 END), 0)  AS rule_hits,
                   COALESCE(SUM(CASE WHEN source = 'MODEL' THEN 1 END), 0)  AS model_hits,
                   COALESCE(SUM(overridden), 0)                             AS overrides
            FROM activity
            WHERE (? IS NULL OR account_id = ?)
            """;

    public SqliteActivityRepository {
        Objects.requireNonNull(db, "db is required");
    }

    @Override
    public void record(ActivityEntry e) {
        try (var con = db.connection(); var ps = con.prepareStatement(INSERT)) {
            ps.setString(1, e.timestamp().toString());
            ps.setString(2, e.accountId());
            ps.setString(3, e.emailId());
            ps.setString(4, e.from());
            ps.setString(5, e.subject());
            ps.setString(6, e.category().name());
            ps.setDouble(7, e.confidence());
            ps.setString(8, e.reason());
            ps.setString(9, e.action().name());
            ps.setString(10, e.source().name());
            ps.setInt(11, e.overridden() ? 1 : 0);
            ps.setString(12, e.folder());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw failure("Could not record activity", ex);
        }
    }

    @Override
    public List<ActivityEntry> recent(String accountId, int limit) {
        try (var con = db.connection(); var ps = con.prepareStatement(RECENT)) {
            ps.setString(1, accountId);
            ps.setString(2, accountId);
            ps.setInt(3, limit);
            try (var rs = ps.executeQuery()) {
                List<ActivityEntry> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return List.copyOf(out);
            }
        } catch (SQLException ex) {
            throw failure("Could not load activity", ex);
        }
    }

    @Override
    public StatsSnapshot stats(String accountId) {
        try (var con = db.connection(); var ps = con.prepareStatement(STATS)) {
            ps.setString(1, accountId);
            ps.setString(2, accountId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return new StatsSnapshot(rs.getLong("total"), rs.getLong("rule_hits"),
                        rs.getLong("model_hits"), rs.getLong("overrides"));
            }
        } catch (SQLException ex) {
            throw failure("Could not load stats", ex);
        }
    }

    private static ActivityEntry map(ResultSet rs) throws SQLException {
        return new ActivityEntry(
                Instant.parse(rs.getString("at")),
                rs.getString("account_id"),
                rs.getString("email_id"),
                rs.getString("sender"),
                rs.getString("subject"),
                Category.valueOf(rs.getString("category")),
                rs.getDouble("confidence"),
                rs.getString("reason"),
                Action.valueOf(rs.getString("action")),
                ClassificationSource.valueOf(rs.getString("source")),
                rs.getInt("overridden") == 1,
                rs.getString("folder"));
    }

    private static PersistenceException failure(String message, SQLException cause) {
        var ex = new PersistenceException(message, cause);
        AppLog.error(SqliteActivityRepository.class, ex.getMessage(), ex);
        return ex;
    }
}
