package dev.jefe.mailsorter.domain.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    /** Providers whose name alone identifies the server (see the UI presets). */
    public static final Set<String> PRESETS = Set.of("gmail", "gmx", "outlook", "yahoo", "icloud");

    /** Stable account key "service|username": rules, folders, activity and saved credentials all use it. */
    public String accountKey() {
        return accountKey(serviceKey(), username);
    }

    /** "gmail", "gmx", ... for a preset; "imap:<host>" for any other server. */
    public String serviceKey() {
        String type = providerType.toLowerCase(java.util.Locale.ROOT);
        return PRESETS.contains(type) ? type : "imap:" + host;
    }

    public static String accountKey(String serviceKey, String username) {
        return serviceKey + "|" + username;
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

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String providerType;
        private String host;
        private int port = -1;                 // force explicit setting
        private String username;
        private String secret;
        private final Map<String, String> options = new HashMap<>();

        private Builder() {}

        public Builder providerType(String providerType) {
            this.providerType = providerType;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder secret(String secret) {
            this.secret = secret;
            return this;
        }

        /** Add or overwrite a single option. */
        public Builder option(String key, String value) {
            Objects.requireNonNull(key, "option key must not be null");
            if (value == null) {
                options.remove(key);
            } else {
                options.put(key, value);
            }
            return this;
        }

        /** Replace all options. */
        public Builder options(Map<String, String> options) {
            this.options.clear();
            if (options != null) {
                this.options.putAll(options);
            }
            return this;
        }

        public MailboxConfig build() {
            return new MailboxConfig(providerType, host, port, username, secret, options);
        }
    }
}