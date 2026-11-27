package dev.jefe.mailsorter.infra.persistence.memory;

import dev.jefe.mailsorter.application.port.out.FolderRepository;
import dev.jefe.mailsorter.domain.model.UserFolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryFolderStore implements FolderRepository {

    private final Map<String, UserFolder> byId = new LinkedHashMap<>(); // insertion order

    @Override
    public synchronized List<UserFolder> findAll() {
        return List.copyOf(byId.values());
    }

    @Override
    public synchronized void save(UserFolder folder) {
        Objects.requireNonNull(folder, "folder is required");
        byId.put(folder.id(), folder);
    }

    @Override
    public synchronized boolean deleteById(String id) {
        return id != null && byId.remove(id) != null;
    }
}
