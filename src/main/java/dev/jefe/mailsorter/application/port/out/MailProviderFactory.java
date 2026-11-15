package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.domain.MailboxConfig;

/** Driven port. Opens and verifies a connection; throws ConnectionFailedException on failure. */
public interface MailProviderFactory {
    MailProvider connect(MailboxConfig config);
}
