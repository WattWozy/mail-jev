package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.port.in.FolderUseCase;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.FolderRepository;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.domain.model.FolderNames;
import dev.jefe.mailsorter.domain.model.UserFolder;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Use case: manage the user folders jev sorts into. Every change is published to the classifier. */
public final class FolderService implements FolderUseCase {

    private final FolderRepository repository;
    private final ConnectionStore connections;
    private final Runnable onFoldersChanged;

    /** Folders are managed for the connected account; {@code onFoldersChanged} runs after every change. */
    public FolderService(FolderRepository repository, ConnectionStore connections, Runnable onFoldersChanged) {
        this.repository = Objects.requireNonNull(repository);
        this.connections = Objects.requireNonNull(connections);
        this.onFoldersChanged = Objects.requireNonNull(onFoldersChanged);
    }

    /** The folders of one account, for the classifier. */
    public List<UserFolder> foldersFor(String accountId) {
        return repository.findAll(accountId);
    }

    @Override
    public List<UserFolder> list() {
        return repository.findAll(activeAccount());
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
        String account = activeAccount();
        if (repository.findAll(account).stream().anyMatch(f -> f.name().equalsIgnoreCase(folderName))) {
            throw new IllegalArgumentException("folder '" + folderName + "' already exists");
        }

        var folder = new UserFolder(UUID.randomUUID().toString(), folderName, text);
        repository.save(account, folder);
        onFoldersChanged.run();
        return folder;
    }

    @Override
    public synchronized void delete(String folderId) {
        if (folderId == null || folderId.isBlank() || !repository.deleteById(activeAccount(), folderId.trim())) {
            throw new IllegalArgumentException("Unknown folder: " + folderId);
        }
        onFoldersChanged.run();
    }

    private String activeAccount() {
        return connections.current().map(MailProvider::accountId).orElseThrow(NotConnectedException::new);
    }
}
