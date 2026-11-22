package dev.jefe.mailsorter.domain;

public record StoredCredential(
        String service,
        Credential credential
) {
}
