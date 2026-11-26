package dev.jefe.mailsorter.infra.persistence;

import dev.jefe.mailsorter.application.port.out.ActivityRepository;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteActivityRepository;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteDatabase;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class SqliteActivityRepositoryTest extends ActivityRepositoryContractTest {

    @TempDir Path dir;

    @Override protected ActivityRepository create() {
        var db = new SqliteDatabase(dir.resolve("activity.db"));
        db.migrate();
        return new SqliteActivityRepository(db);
    }
}
