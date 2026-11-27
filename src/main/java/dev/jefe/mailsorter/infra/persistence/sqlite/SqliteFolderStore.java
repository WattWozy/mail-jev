package dev.jefe.mailsorter.infra.persistence.sqlite;

import dev.jefe.mailsorter.application.exception.PersistenceException;
import dev.jefe.mailsorter.application.port.out.FolderRepository;
import dev.jefe.mailsorter.domain.model.UserFolder;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record SqliteFolderStore(SqliteDatabase db) implements FolderRepository {

    private static final String SELECT_ALL = """
            SELECT id, name, description
            FROM user_folders
            ORDER BY created_at ASC, rowid ASC
            """;

    private static final String UPSERT = """
            INSERT INTO user_folders(id, name, description, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name        = excluded.name,
                description = excluded.description
            """;

    private static final String DELETE_BY_ID = "DELETE FROM user_folders WHERE id = ?";

    public SqliteFolderStore {
        Objects.requireNonNull(db, "db is required");
    }

    @Override
    public List<UserFolder> findAll() {
        try (var con = db.connection(); var ps = con.prepareStatement(SELECT_ALL); var rs = ps.executeQuery()) {
            List<UserFolder> folders = new ArrayList<>();
            while (rs.next()) {
                folders.add(new UserFolder(rs.getString("id"), rs.getString("name"), rs.getString("description")));
            }
            return List.copyOf(folders);
        } catch (SQLException e) {
            throw failure("Could not load user folders", e);
        }
    }

    @Override
    public void save(UserFolder folder) {
        Objects.requireNonNull(folder, "folder is required");
        try (var con = db.connection(); var ps = con.prepareStatement(UPSERT)) {
            ps.setString(1, folder.id());
            ps.setString(2, folder.name());
            ps.setString(3, folder.description());
            ps.setString(4, Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("Could not save user folder", e);
        }
    }

    @Override
    public boolean deleteById(String id) {
        if (id == null || id.isBlank()) return false;
        try (var con = db.connection(); var ps = con.prepareStatement(DELETE_BY_ID)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw failure("Could not delete user folder", e);
        }
    }

    private static PersistenceException failure(String message, SQLException cause) {
        var ex = new PersistenceException(message, cause);
        AppLog.error(SqliteFolderStore.class, ex.getMessage(), ex);
        return ex;
    }
}
