package dev.jefe.mailsorter.infra.persistence.memory;

import dev.jefe.mailsorter.application.port.out.CredentialStore;
import dev.jefe.mailsorter.domain.model.Credential;
import dev.jefe.mailsorter.domain.model.StoredCredential;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCredentialStore implements CredentialStore {

    private final Map<String, StoredCredential> store = new ConcurrentHashMap<>();

    private static String key(String service, String username) {
        return service + '\0' + username;
    }

    @Override
    public void save(String service, String username, String secret) {
        Objects.requireNonNull(secret, "secret must not be null");
        store.put(key(service, username),
                new StoredCredential(service, new Credential(username, secret)));
    }

    @Override
    public Optional<Credential> find(String service) {
        // same semantics as before: first match for that service, if any
        return store.values().stream()
                .filter(sc -> sc.service().equals(service))
                .map(StoredCredential::credential)
                .findFirst();
    }

    @Override
    public List<StoredCredential> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void delete(String service, String username) {
        store.remove(key(service, username));
    }
}