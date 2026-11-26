package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.domain.model.Credential;
import dev.jefe.mailsorter.domain.model.StoredCredential;

import java.util.List;
import java.util.Optional;

public interface CredentialStore {

    void save(String service, String username, String secret);

    Optional<Credential> find(String service);

    List<StoredCredential> findAll();

    void delete(String service, String username);

}