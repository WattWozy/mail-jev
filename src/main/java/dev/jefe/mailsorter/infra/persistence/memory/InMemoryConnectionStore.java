package dev.jefe.mailsorter.infra.persistence.memory;

import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.util.Optional;

/** Holds the single active mailbox session; replacing it closes the previous one. */
public final class InMemoryConnectionStore implements ConnectionStore {
    private volatile MailProvider current;

    @Override
    public synchronized void save(MailProvider provider) {
        MailProvider previous = this.current;
        this.current = provider;
        if (previous != null && previous != provider) {
            try {
                previous.close();
            } catch (RuntimeException e) {
                AppLog.warn(InMemoryConnectionStore.class, "Closing previous mailbox session failed: " + e);
            }
        }
    }

    @Override
    public Optional<MailProvider> current() { return Optional.ofNullable(current); }
}
