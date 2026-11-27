package dev.jefe.mailsorter.infra.persistence.sqlite;

import dev.jefe.mailsorter.domain.model.UserFolder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** V8 makes rules and folders per account: rows made before it are copied to every saved account. */
class PerAccountMigrationTest {

    @TempDir Path dir;

    @Test
    void existingRulesAndFoldersAreCopiedToEverySavedAccount() throws SQLException {
        Path file = dir.resolve("app.db");
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        Flyway.configure().dataSource(url, null, null).target("7").load().migrate();

        try (var con = DriverManager.getConnection(url); var st = con.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO credentials(service, username, secret, created_at, updated_at) VALUES
                      ('gmx', 'me@gmx.com', 's', 't', 't'),
                      ('gmail', 'me@gmail.com', 's', 't', 't')""");
            st.executeUpdate("""
                    INSERT INTO prefilter_rules(id, match_field, pattern, action, category, created_at, folder)
                    VALUES ('r1', 'FROM_DOMAIN', 'ikea.com', 'MOVE_TO_FOLDER', 'OTHER', '2026-01-01T00:00:00Z', 'Ikea')""");
            st.executeUpdate("""
                    INSERT INTO user_folders(id, name, description, created_at)
                    VALUES ('f1', 'Ikea', 'IKEA offers', '2026-01-01T00:00:00Z')""");
        }

        var db = new SqliteDatabase(file);
        db.migrate();

        var rules = new SqliteRuleStore(db);
        var folders = new SqliteFolderStore(db);
        for (String account : new String[] {"gmx|me@gmx.com", "gmail|me@gmail.com"}) {
            assertThat(rules.findAll(account)).singleElement()
                    .satisfies(r -> assertThat(r.folder()).isEqualTo("Ikea"));
            assertThat(folders.findAll(account)).containsExactly(new UserFolder("f1", "Ikea", "IKEA offers"));
        }
    }

    @Test
    void anEmptyDatabaseMigratesCleanly() {
        var db = new SqliteDatabase(dir.resolve("fresh.db"));
        db.migrate();

        assertThat(new SqliteRuleStore(db).findAll("gmx|me@gmx.com")).isEmpty();
        assertThat(new SqliteFolderStore(db).findAll("gmx|me@gmx.com")).isEmpty();
    }
}
