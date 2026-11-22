package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.domain.Credential;
import dev.jefe.mailsorter.domain.StoredCredential;

import java.util.List;
import java.util.Optional;

public interface CredentialStore {

    void save(String service, String username, String secret);

    Optional<Credential> find(String service);

    List<StoredCredential> findAll();

    void delete(String service, String username);

}