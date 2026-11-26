package dev.jefe.mailsorter.infra.persistence.sqlite;

import dev.jefe.mailsorter.application.port.out.dto.AutopilotSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteAutopilotSettingsStoreTest {

    @TempDir
    Path dir;

    private SqliteDatabase db;
    private SqliteAutopilotSettingsStore store;

    @BeforeEach
    void setUp() {
        db = new SqliteDatabase(dir.resolve("test.db"));
        db.migrate();
        store = new SqliteAutopilotSettingsStore(db);
    }

    @Test
    void isEmptyBeforeAnythingWasSaved() {
        assertThat(store.load()).isEmpty();
    }

    @Test
    void roundTripsSettings() {
        var settings = new AutopilotSettings(true, 300, 25, "gmx|me@gmx.com");

        store.save(settings);

        assertThat(store.load()).contains(settings);
    }

    @Test
    void savingAgainOverwritesTheSingleRow() {
        store.save(new AutopilotSettings(true, 300, 25, "gmx|me@gmx.com"));

        store.save(new AutopilotSettings(false, 60, 5, null));

        assertThat(store.load()).contains(new AutopilotSettings(false, 60, 5, null));
    }

    @Test
    void survivesReopeningTheDatabase() {
        var settings = new AutopilotSettings(true, 120, 10, "gmail|me@gmail.com");
        store.save(settings);

        var reopened = new SqliteDatabase(dir.resolve("test.db"));
        reopened.migrate();

        assertThat(new SqliteAutopilotSettingsStore(reopened).load()).contains(settings);
    }
}
