package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.domain.Credential;

import java.util.Optional;

public interface CredentialStore {

    void save(String service, String username, String secret);

    Optional<Credential> find(String service);

    void delete(String service, String username);

}