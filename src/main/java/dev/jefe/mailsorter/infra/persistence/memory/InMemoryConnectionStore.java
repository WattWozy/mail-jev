package dev.jefe.mailsorter.infra.persistence.memory;

import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.MailProvider;

import java.util.Optional;

public final class InMemoryConnectionStore implements ConnectionStore {
    private volatile MailProvider current;
    @Override public void save(MailProvider provider) { this.current = provider; }
    @Override public Optional<MailProvider> current() { return Optional.ofNullable(current); }
}