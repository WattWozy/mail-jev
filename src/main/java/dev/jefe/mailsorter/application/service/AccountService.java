package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.port.in.AccountStatus;
import dev.jefe.mailsorter.application.port.in.AccountView;
import dev.jefe.mailsorter.application.port.in.ConnectUseCase;
import dev.jefe.mailsorter.application.port.in.ListAccountsUseCase;
import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.CredentialStore;
import dev.jefe.mailsorter.application.port.out.MailProviderFactory;
import dev.jefe.mailsorter.domain.StoredCredential;
import dev.jefe.mailsorter.domain.config.MailboxConfig;

import java.util.List;
import java.util.Map;

public final class AccountService implements ConnectUseCase, ListAccountsUseCase {

    private static final int IMAPS_PORT = 993;

    // TODO: share this table with the web layer's presets instead of keeping two copies.
    private static final Map<String, String> PRESET_HOSTS = Map.of(
            "gmail", "imap.gmail.com",
            "gmx", "imap.gmx.com",
            "outlook", "outlook.office365.com",
            "yahoo", "imap.mail.yahoo.com",
            "icloud", "imap.mail.me.com");

    private final MailProviderFactory factory;
    private final ConnectionStore connectionStore;
    private final CredentialStore credentialStore;

    private volatile String activeAccountId;   // null = nothing connected yet

    public AccountService(MailProviderFactory factory,
                          ConnectionStore connectionStore,
                          CredentialStore credentialStore) {
        this.factory = factory;
        this.connectionStore = connectionStore;
        this.credentialStore = credentialStore;
    }

    // -- ConnectUseCase: a new account typed in by the user ---------------------

    @Override
    public void execute(MailboxConfig config) {
        connectAndActivate(config);                      // throws on bad credentials, nothing saved
        credentialStore.save(serviceKey(config), config.username(), config.secret());
    }

    // -- ListAccountsUseCase ----------------------------------------------------

    @Override
    public List<AccountView> list() {
        String active = activeAccountId;
        return credentialStore.findAll().stream()
                .map(sc -> {
                    String username = sc.credential().username();
                    String id = accountId(sc.service(), username);
                    var status = id.equals(active) ? AccountStatus.ACTIVE : AccountStatus.SAVED;
                    return new AccountView(id, sc.service(), username, status);
                })
                .toList();
    }

    @Override
    public void connectSaved(String accountId) {
        String[] parts = accountId.split("\\|", 2);      // "service|username"
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid accountId: " + accountId);
        }
        StoredCredential stored = credentialStore.findAll().stream()
                .filter(sc -> sc.service().equals(parts[0])
                        && sc.credential().username().equals(parts[1]))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No saved credentials for " + accountId));

        connectAndActivate(rebuildConfig(stored));       // no re-save: the row already exists
    }

    // -- helpers ------------------------------------------------------------------

    private void connectAndActivate(MailboxConfig config) {
        connectionStore.save(factory.connect(config));   // a failure leaves the old session intact
        activeAccountId = accountId(serviceKey(config), config.username());
    }

    private static String serviceKey(MailboxConfig config) {
        String type = config.providerType().toLowerCase();
        return PRESET_HOSTS.containsKey(type) ? type : "imap:" + config.host();
    }

    private static String accountId(String service, String username) {
        return service + "|" + username;
    }

    private static MailboxConfig rebuildConfig(StoredCredential stored) {
        String service = stored.service();
        String providerType;
        String host;
        if (service.startsWith("imap:")) {
            providerType = "imap";
            host = service.substring("imap:".length());
        } else if (PRESET_HOSTS.containsKey(service)) {
            providerType = service;
            host = PRESET_HOSTS.get(service);
        } else {
            throw new IllegalStateException("Cannot rebuild a connection for service '" + service + "'");
        }
        return MailboxConfig.builder()
                .providerType(providerType)
                .host(host)
                .port(IMAPS_PORT)
                .username(stored.credential().username())
                .secret(stored.credential().secret())
                .build();
    }
}