package dev.jefe.mailsorter.infra.persistence.memory;

import dev.jefe.mailsorter.application.port.out.FolderRepository;
import dev.jefe.mailsorter.domain.model.UserFolder;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryFolderStore implements FolderRepository {

    private final Map<String, Map<String, UserFolder>> byAccount = new HashMap<>(); // inner: insertion order

    @Override
    public synchronized List<UserFolder> findAll(String accountId) {
        return List.copyOf(byAccount.getOrDefault(accountId, Map.of()).values());
    }

    @Override
    public synchronized void save(String accountId, UserFolder folder) {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(folder, "folder is required");
        byAccount.computeIfAbsent(accountId, k -> new LinkedHashMap<>()).put(folder.id(), folder);
    }

    @Override
    public synchronized boolean deleteById(String accountId, String id) {
        var folders = byAccount.get(accountId);
        return folders != null && id != null && folders.remove(id) != null;
    }
}
