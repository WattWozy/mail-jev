package dev.jefe.mailsorter.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class EmailTest {
    @Test
    void headerLookupIgnoresCase() {
        var email = new Email("1", "a@b.com", "s", "snip", Map.of("List-Unsubscribe", "<mailto:x@y.com>"));

        assertThat(email.header("list-unsubscribe")).contains("<mailto:x@y.com>");
        assertThat(email.header("LIST-UNSUBSCRIBE")).contains("<mailto:x@y.com>");
    }

    @Test
    void missingHeaderIsEmpty() {
        var email = new Email("1", "a@b.com", "s", "snip", Map.of());

        assertThat(email.header("List-Unsubscribe")).isEmpty();
    }
}