package dev.jefe.mailsorter.domain.model;

public record StoredCredential(
        String service,
        Credential credential
) {
}
