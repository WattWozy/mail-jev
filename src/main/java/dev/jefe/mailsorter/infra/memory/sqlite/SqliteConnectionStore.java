package dev.jefe.mailsorter.infra.memory.sqlite;

import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.infra.sqlite.SqliteDatabase;

import java.util.Optional;

public final class SqliteConnectionStore implements ConnectionStore {

    private final SqliteDatabase db;

    public SqliteConnectionStore(SqliteDatabase db) { this.db = db; }

    private volatile MailProvider current;
    @Override public void save(MailProvider provider) { this.current = provider; }
    @Override public Optional<MailProvider> current() { return Optional.ofNullable(current); }
}
