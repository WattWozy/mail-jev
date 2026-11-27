package dev.jefe.mailsorter.domain;

import dev.jefe.mailsorter.domain.config.MailboxConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;


class MailboxConfigTest {
    @Test
    void rejectsBlankHost() {
        assertThatThrownBy(() -> new MailboxConfig("imap", " ", 993, "me@example.com", "secret", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void aPresetProviderIsKeyedByItsNameAndUsername() {
        var config = new MailboxConfig("Gmail", "imap.gmail.com", 993, "me@gmail.com", "secret", Map.of());

        org.assertj.core.api.Assertions.assertThat(config.accountKey()).isEqualTo("gmail|me@gmail.com");
    }

    @Test
    void aCustomImapServerIsKeyedByItsHostAndUsername() {
        var config = new MailboxConfig("imap", "mail.example.com", 993, "me@example.com", "secret", Map.of());

        org.assertj.core.api.Assertions.assertThat(config.accountKey()).isEqualTo("imap:mail.example.com|me@example.com");
    }
}