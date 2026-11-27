package dev.jefe.mailsorter.infra.persistence.sqlite;

import dev.jefe.mailsorter.domain.model.UserFolder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V9: every folder a rule files into becomes a defined folder (described by its rules' patterns).
 * V10: activity rows written with the old "username@host" account id get the "service|username" key.
 */
class FolderAndActivityMigrationTest {

    private static final String GMX = "gmx|me@gmx.com";

    @TempDir Path dir;

    private String urlMigratedTo(String version) {
        String url = "jdbc:sqlite:" + dir.resolve("app.db").toAbsolutePath();
        Flyway.configure().dataSource(url, null, null).target(version).load().migrate();
        return url;
    }

    private SqliteDatabase migrateFully() {
        var db = new SqliteDatabase(dir.resolve("app.db"));
        db.migrate();
        return db;
    }

    private static void rule(java.sql.Statement st, String id, String pattern, String folder) throws SQLException {
        st.executeUpdate("INSERT INTO prefilter_rules(account_id, id, match_field, pattern, action, category, folder, created_at) "
                + "VALUES ('" + GMX + "', '" + id + "', 'FROM_ADDRESS', '" + pattern + "', "
                + (folder == null ? "'ARCHIVE'" : "'MOVE_TO_FOLDER'") + ", 'OTHER', "
                + (folder == null ? "NULL" : "'" + folder + "'") + ", '2026-11-27T16:00:0" + id.length() + "Z')");
    }

    @Test
    void ruleFoldersBecomeDefinedFoldersOncePerNameIgnoringCase() throws SQLException {
        String url = urlMigratedTo("8");
        try (var con = DriverManager.getConnection(url); var st = con.createStatement()) {
            rule(st, "a", "jobalerts-noreply@linkedin.com", "Job Leads");
            rule(st, "bb", "noreply@glassdoor.com", "job leads");
            rule(st, "ccc", "news@ikea.com", "ikea");
            rule(st, "dddd", "black friday", null);
            st.executeUpdate("INSERT INTO user_folders(account_id, id, name, description, created_at) "
                    + "VALUES ('" + GMX + "', 'f1', 'Ikea', 'IKEA offers', '2026-11-27T15:00:00Z')");
        }

        var db = migrateFully();

        List<UserFolder> folders = new SqliteFolderStore(db).findAll(GMX);
        assertThat(folders).extracting(UserFolder::name).containsExactlyInAnyOrder("Ikea", "Job Leads");
        assertThat(folders).filteredOn(f -> f.name().equals("Ikea"))
                .singleElement().extracting(UserFolder::description).isEqualTo("IKEA offers"); // kept as it was
        assertThat(folders).filteredOn(f -> f.name().equals("Job Leads"))
                .singleElement().extracting(UserFolder::description).asString()
                .contains("jobalerts-noreply@linkedin.com").contains("noreply@glassdoor.com");

        // every rule now names its folder exactly as defined
        assertThat(new SqliteRuleStore(db).findAll(GMX))
                .extracting(r -> r.pattern() + " -> " + r.folder())
                .containsExactlyInAnyOrder(
                        "jobalerts-noreply@linkedin.com -> Job Leads",
                        "noreply@glassdoor.com -> Job Leads",
                        "news@ikea.com -> Ikea",
                        "black friday -> null");
    }

    @Test
    void oldActivityAccountIdsGetTheNewKey() throws SQLException {
        String url = urlMigratedTo("9");
        try (var con = DriverManager.getConnection(url); var st = con.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO credentials(service, username, secret, created_at, updated_at) VALUES
                      ('gmail', 'me@gmail.com', 's', 't', 't'),
                      ('imap:imap.gmx.com', 'me@gmx.es', 's', 't', 't')""");
            for (String account : new String[] {"me@gmail.com@imap.gmail.com", "me@gmx.es@imap.gmx.com",
                    "gmail|me@gmail.com", "stranger@nowhere.example"}) {
                st.executeUpdate("INSERT INTO activity(at, account_id, email_id, category, confidence, action, source, overridden) "
                        + "VALUES ('2026-11-27T13:17:00Z', '" + account + "', 'e', 'OTHER', 1.0, 'KEEP', 'RULE', 0)");
            }
        }

        migrateFully();

        List<String> accounts = new ArrayList<>();
        try (var con = DriverManager.getConnection(url);
             var rs = con.createStatement().executeQuery("SELECT account_id FROM activity ORDER BY id")) {
            while (rs.next()) accounts.add(rs.getString(1));
        }
        assertThat(accounts).containsExactly(
                "gmail|me@gmail.com",
                "imap:imap.gmx.com|me@gmx.es",
                "gmail|me@gmail.com",
                "stranger@nowhere.example");   // no saved account to map it to: left alone
    }
}
