package dev.jefe.mailsorter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jefe.mailsorter.application.Fakes.*;
import dev.jefe.mailsorter.application.exception.ConnectionFailedException;
import dev.jefe.mailsorter.application.service.ConnectService;
import dev.jefe.mailsorter.domain.MailboxConfig;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectUseCaseTest {

    private FakeMailProviderFactory factory;
    private InMemoryConnectionStore store;
    private ConnectService connect;

    private final MailboxConfig config =
            new MailboxConfig("imap", "imap.example.com", 993, "me@example.com", "secret", Map.of());

    @BeforeEach
    void setUp() {
        factory = new FakeMailProviderFactory();
        store = new InMemoryConnectionStore();
        connect = new ConnectService(factory, store);
    }

    @Test
    void storesTheProviderWhenConnectionSucceeds() {
        connect.execute(config);

        assertThat(store.current()).containsSame(factory.toReturn);
        assertThat(factory.lastConfig).isEqualTo(config);
    }

    @Test
    void doesNotStoreAnythingWhenConnectionFails() {
        factory.failure = new ConnectionFailedException("bad credentials");

        assertThatThrownBy(() -> connect.execute(config)).isInstanceOf(ConnectionFailedException.class);
        assertThat(store.current()).isEmpty();
    }

    @Test
    void replacesThePreviousConnection() {
        connect.execute(config);
        var second = new FakeMailProvider();
        factory.toReturn = second;

        connect.execute(config);

        assertThat(store.current()).containsSame(second);
    }
}
