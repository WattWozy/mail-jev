package dev.jefe.mailsorter.application.port.out;

import java.util.Optional;

/** Driven port. Holds the currently active mailbox connection. */
public interface ConnectionStore {
    void save(MailProvider provider);
    Optional<MailProvider> current();
}
