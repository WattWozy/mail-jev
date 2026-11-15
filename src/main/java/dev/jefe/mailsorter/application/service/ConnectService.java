package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.port.in.ConnectUseCase;
import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.application.port.out.MailProviderFactory;
import dev.jefe.mailsorter.domain.MailboxConfig;

/** Use case: connect to the customer's email provider. */
public final class ConnectService implements ConnectUseCase {

    private final MailProviderFactory factory;
    private final ConnectionStore store;

    public ConnectService(MailProviderFactory factory, ConnectionStore store) {
        this.factory = factory;
        this.store = store;
    }

    public void execute(MailboxConfig config) {
        MailProvider provider = factory.connect(config); // throws -> nothing is stored
        store.save(provider);
    }
}
