package dev.jefe.mailsorter.infra.mail;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import dev.jefe.mailsorter.application.exception.ConnectionFailedException;
import dev.jefe.mailsorter.domain.config.MailboxConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

class ImapMailProviderFactoryTest {

    @RegisterExtension
    final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("me@example.com", "secret"));

    private final ImapMailProviderFactory factory = new ImapMailProviderFactory();

    private MailboxConfig config(int port, String password) {
        return new MailboxConfig("imap", "127.0.0.1", port, "me@example.com", password, Map.of("ssl", "false"));
    }

    @Test
    void connectsWithValidCredentials() {
        assertThat(factory.connect(config(greenMail.getImap().getPort(), "secret"))).isNotNull();
    }

    @Test
    void wrongPasswordFailsWithConnectionFailed() {
        assertThatThrownBy(() -> factory.connect(config(greenMail.getImap().getPort(), "nope")))
                .isInstanceOf(ConnectionFailedException.class)
                .hasMessageNotContaining("nope");
    }

    @Test
    void unreachableServerFailsWithConnectionFailed() {
        assertThatThrownBy(() -> factory.connect(config(1, "secret")))
                .isInstanceOf(ConnectionFailedException.class);
    }
}
