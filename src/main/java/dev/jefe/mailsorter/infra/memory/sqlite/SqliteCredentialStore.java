package dev.jefe.mailsorter.infra.memory.sqlite;

import dev.jefe.mailsorter.application.exception.PersistenceException;
import dev.jefe.mailsorter.application.port.out.CredentialStore;
import dev.jefe.mailsorter.domain.Credential;
import dev.jefe.mailsorter.infra.logging.AppLog;
import dev.jefe.mailsorter.infra.sqlite.SqliteDatabase;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public record SqliteCredentialStore(SqliteDatabase db) implements CredentialStore {

    private static final String INSERT = """
        INSERT INTO credentials(service, username, secret, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(service, username) DO UPDATE SET
            secret     = excluded.secret,
            updated_at = excluded.updated_at
        """;

    private static final String FIND_BY_SERVICE = """
        SELECT username, secret
        FROM credentials
        WHERE service = ?
        """;

    private static final String DELETE = """
        DELETE FROM credentials
        WHERE service = ? AND username = ?
        """;

    public void save(String service, String username, String secret) {
        var now = Instant.now().toString();
        try (var con = db.connection(); var ps = con.prepareStatement(INSERT)) {
            ps.setString(1, service);
            ps.setString(2, username);
            ps.setString(3, secret);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            var ex = new PersistenceException("Could not save credential for service=" + service, e);
            AppLog.error(SqliteCredentialStore.class, ex.getMessage(), ex);
            throw ex;
        }
    }

    public Optional<Credential> find(String service) {
        try (var con = db.connection(); var ps = con.prepareStatement(FIND_BY_SERVICE)) {
            ps.setString(1, service);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Credential(
                        rs.getString("username"),
                        rs.getString("secret")
                ));
            }
        } catch (SQLException e) {
            var ex = new PersistenceException("Could not load credential for service=" + service, e);
            AppLog.error(SqliteCredentialStore.class, ex.getMessage(), ex);
            throw ex;
        }
    }

    public void delete(String service, String username) {
        try (var con = db.connection(); var ps = con.prepareStatement(DELETE)) {
            ps.setString(1, service);
            ps.setString(2, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            var ex = new PersistenceException("Could not delete credential", e);
            AppLog.error(SqliteCredentialStore.class, ex.getMessage(), ex);
            throw ex;
        }
    }

}
