package dev.jefe.mailsorter.infra.memory.inMemory;

import dev.jefe.mailsorter.application.port.out.CredentialStore;
import dev.jefe.mailsorter.domain.Credential;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCredentialStore implements CredentialStore {

    // key = service
    private final Map<String, Credential> store = new ConcurrentHashMap<>();

    @Override
    public void save(String service, String username, String secret) {
        store.put(service, new Credential(username, secret));
    }

    @Override
    public Optional<Credential> find(String service) {
        return Optional.ofNullable(store.get(service));
    }

    @Override
    public void delete(String service, String username) {
        store.computeIfPresent(service, (k, existing) ->
                existing.username().equals(username) ? null : existing
        );
    }
}