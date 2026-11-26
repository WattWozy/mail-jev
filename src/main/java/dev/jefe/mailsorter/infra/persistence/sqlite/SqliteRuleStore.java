package dev.jefe.mailsorter.infra.persistence.sqlite;
import dev.jefe.mailsorter.application.exception.PersistenceException;
import dev.jefe.mailsorter.application.port.out.RuleRepository;
import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;
import dev.jefe.mailsorter.application.service.prefilter.rules.RuleField;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record SqliteRuleStore(SqliteDatabase db) implements RuleRepository {

    private static final String SELECT_ALL = """
            SELECT id, match_field, pattern, action, category
            FROM prefilter_rules
            ORDER BY created_at ASC, rowid ASC
            """;

    private static final String UPSERT = """
            INSERT INTO prefilter_rules(id, match_field, pattern, action, category, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                match_field = excluded.match_field,
                pattern     = excluded.pattern,
                action      = excluded.action,
                category    = excluded.category
            """;

    private static final String DELETE_BY_ID = """
            DELETE FROM prefilter_rules WHERE id = ?
            """;

    public SqliteRuleStore {
        Objects.requireNonNull(db, "db is required");
    }

    @Override
    public List<PrefilterRule> findAll() {
        try (var con = db.connection();
             var ps = con.prepareStatement(SELECT_ALL);
             var rs = ps.executeQuery()) {

            List<PrefilterRule> rules = new ArrayList<>();
            while (rs.next()) {
                rules.add(mapRow(rs));
            }
            return List.copyOf(rules);
        } catch (SQLException e) {
            var ex = new PersistenceException("Could not load prefilter rules", e);
            AppLog.error(SqliteRuleStore.class, ex.getMessage(), ex);
            throw ex;
        }
    }

    @Override
    public void save(PrefilterRule rule) {
        Objects.requireNonNull(rule, "rule is required");
        try (var con = db.connection();
             var ps = con.prepareStatement(UPSERT)) {

            ps.setString(1, rule.id());
            ps.setString(2, rule.field().name());
            ps.setString(3, rule.pattern());
            ps.setString(4, rule.action().name());
            ps.setString(5, rule.category().name());
            ps.setString(6, Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            var ex = new PersistenceException("Could not save prefilter rule", e);
            AppLog.error(SqliteRuleStore.class, ex.getMessage(), ex);
            throw ex;
        }
    }

    @Override
    public boolean deleteById(String id) {
        if (id == null || id.isBlank()) return false;
        try (var con = db.connection();
             var ps = con.prepareStatement(DELETE_BY_ID)) {

            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            var ex = new PersistenceException("Could not delete prefilter rule", e);
            AppLog.error(SqliteRuleStore.class, ex.getMessage(), ex);
            throw ex;
        }
    }

    private static PrefilterRule mapRow(ResultSet rs) throws SQLException {
        return new PrefilterRule(
                rs.getString("id"),
                RuleField.valueOf(rs.getString("match_field")),
                rs.getString("pattern"),
                Action.valueOf(rs.getString("action")),
                Category.valueOf(rs.getString("category"))
        );
    }
}