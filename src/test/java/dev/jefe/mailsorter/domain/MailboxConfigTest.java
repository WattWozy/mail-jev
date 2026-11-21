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
}