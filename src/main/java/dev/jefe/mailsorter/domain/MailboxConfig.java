package dev.jefe.mailsorter.domain;

import java.util.Map;

/** Provider-agnostic connection settings pasted in by the customer. */
public record MailboxConfig(
        String providerType, String host, int port, String username, String secret, Map<String, String> options) {

    public MailboxConfig {
        require(providerType, "providerType");
        require(host, "host");
        require(username, "username");
        require(secret, "secret");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in 1..65535");
        }
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    @Override
    public String toString() { // never leak the secret into logs
        return "MailboxConfig[" + providerType + "://" + username + "@" + host + ":" + port + "]";
    }
}
