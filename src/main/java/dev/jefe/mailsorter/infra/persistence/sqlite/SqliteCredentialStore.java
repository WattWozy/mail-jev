package dev.jefe.mailsorter.infra.persistence.sqlite;

import dev.jefe.mailsorter.application.exception.CryptoException;
import dev.jefe.mailsorter.application.exception.PersistenceException;
import dev.jefe.mailsorter.application.port.out.CredentialStore;
import dev.jefe.mailsorter.application.port.out.SecretCipher;
import dev.jefe.mailsorter.domain.model.Credential;
import dev.jefe.mailsorter.domain.model.StoredCredential;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SqliteCredentialStore(SqliteDatabase db, SecretCipher cipher) implements CredentialStore {

    private static final String FIND_ALL =
            "SELECT service, username, secret FROM credentials ORDER BY id";

    private static final String UPSERT = """
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

    @Override
    public void save(String service, String username, String secret) {
        Objects.requireNonNull(secret, "secret must not be null");
        String now = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        try (var con = db.connection(); var ps = con.prepareStatement(UPSERT)) {
            ps.setString(1, service);
            ps.setString(2, username);
            ps.setString(3, cipher.encrypt(secret));
            ps.setString(4, now);   // created_at: only used on first insert
            ps.setString(5, now);   // updated_at
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not save credential for service=" + service, e);
        }
    }

    public Optional<Credential> find(String service) {
        try (var con = db.connection(); var ps = con.prepareStatement(FIND_BY_SERVICE)) {
            ps.setString(1, service);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Credential(
                        rs.getString("username"),
                        cipher.decrypt(rs.getString("secret"))
                ));
            }
        } catch (SQLException e) {
            var ex = new PersistenceException("Could not load credential for service=" + service, e);
            AppLog.error(SqliteCredentialStore.class, ex.getMessage(), ex);
            throw ex;
        }
    }

    @Override
    public List<StoredCredential> findAll() {
        var result = new ArrayList<StoredCredential>();
        try (var con = db.connection();
             var ps = con.prepareStatement(FIND_ALL);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                String service = rs.getString("service");
                String username = rs.getString("username");
                try {
                    result.add(new StoredCredential(
                            service, new Credential(username, cipher.decrypt(rs.getString("secret")))));
                } catch (CryptoException e) {
                    // one unreadable row must not hide the others; never log the secret
                    AppLog.error(SqliteCredentialStore.class,
                            "Could not decrypt credential for " + service + " / " + username, e);
                }
            }
            return result;
        } catch (SQLException e) {
            var ex = new PersistenceException("Could not load credentials", e);
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
