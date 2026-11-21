package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.port.in.ConnectUseCase;
import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.CredentialStore;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.application.port.out.MailProviderFactory;
import dev.jefe.mailsorter.domain.config.MailboxConfig;

/** Use case: connect to the customer's email provider. */
public final class ConnectService implements ConnectUseCase {

    private final MailProviderFactory factory;
    private final ConnectionStore connectionStore;
    private final CredentialStore credentialStore;

    public ConnectService(MailProviderFactory factory,
                          ConnectionStore connectionStore,
                          CredentialStore credentialStore) {
        this.factory = factory;
        this.connectionStore = connectionStore;
        this.credentialStore = credentialStore;
    }

    @Override
    public void execute(MailboxConfig config) {
        // 1. establish the live connection (throws if credentials are wrong)
        MailProvider provider = factory.connect(config);

        // 2. keep the live session
        connectionStore.save(provider);

        // 3. persist the credentials for later reconnection
        credentialStore.save(
                serviceKey(config),
                config.username(),
                config.secret()
        );
    }

    private static String serviceKey(MailboxConfig config) {
        // Choose whatever key strategy you prefer
        return switch (config.providerType().toLowerCase()) {
            case "gmx", "gmail", "outlook", "yahoo", "icloud" -> config.providerType().toLowerCase();
            default -> "imap:" + config.host();
        };
    }
}
