package dev.jefe.mailsorter.infra.persistence;

import dev.jefe.mailsorter.application.port.out.FolderRepository;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteDatabase;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteFolderStore;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class SqliteFolderStoreTest extends FolderRepositoryContractTest {

    @TempDir Path dir;

    @Override protected FolderRepository create() {
        var db = new SqliteDatabase(dir.resolve("folders.db"));
        db.migrate();
        return new SqliteFolderStore(db);
    }
}
