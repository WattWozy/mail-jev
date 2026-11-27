package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.port.in.FolderUseCase;
import dev.jefe.mailsorter.application.port.out.FolderRepository;
import dev.jefe.mailsorter.domain.model.FolderNames;
import dev.jefe.mailsorter.domain.model.UserFolder;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Use case: manage the user folders jev sorts into. Every change is published to the classifier. */
public final class FolderService implements FolderUseCase {

    private final FolderRepository repository;
    private final Consumer<List<UserFolder>> onFoldersChanged;

    public FolderService(FolderRepository repository, Consumer<List<UserFolder>> onFoldersChanged) {
        this.repository = Objects.requireNonNull(repository);
        this.onFoldersChanged = Objects.requireNonNull(onFoldersChanged);
    }

    /** Call once in CompositionRoot after constructing the classifier. */
    public void reload() {
        onFoldersChanged.accept(repository.findAll());
    }

    @Override
    public List<UserFolder> list() {
        return repository.findAll();
    }

    @Override
    public synchronized UserFolder add(String name, String description) {
        String folderName = FolderNames.validate(name);
        if (folderName == null) {
            throw new IllegalArgumentException("folder name is required");
        }
        String text = description == null ? "" : description.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(
                    "description is required: say in one sentence what belongs in " + folderName);
        }
        if (text.length() > UserFolder.MAX_DESCRIPTION) {
            throw new IllegalArgumentException(
                    "description is longer than " + UserFolder.MAX_DESCRIPTION + " characters");
        }
        if (repository.findAll().stream().anyMatch(f -> f.name().equalsIgnoreCase(folderName))) {
            throw new IllegalArgumentException("folder '" + folderName + "' already exists");
        }

        var folder = new UserFolder(UUID.randomUUID().toString(), folderName, text);
        repository.save(folder);
        reload();
        return folder;
    }

    @Override
    public synchronized void delete(String folderId) {
        if (folderId == null || folderId.isBlank() || !repository.deleteById(folderId.trim())) {
            throw new IllegalArgumentException("Unknown folder: " + folderId);
        }
        reload();
    }
}
