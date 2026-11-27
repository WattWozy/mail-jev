package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.port.in.FolderUseCase;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.FolderRepository;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.application.port.out.RuleRepository;
import dev.jefe.mailsorter.application.exception.FolderInUseException;
import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;
import java.util.stream.Collectors;
import dev.jefe.mailsorter.domain.model.FolderNames;
import dev.jefe.mailsorter.domain.model.UserFolder;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Use case: manage the user folders jev sorts into. Every change is published to the classifier. */
public final class FolderService implements FolderUseCase {

    private final FolderRepository repository;
    private final RuleRepository rules;
    private final ConnectionStore connections;
    private final Runnable onFoldersChanged;

    /** Folders are managed for the connected account; {@code onFoldersChanged} runs after every change. */
    public FolderService(FolderRepository repository, RuleRepository rules, ConnectionStore connections,
                         Runnable onFoldersChanged) {
        this.repository = Objects.requireNonNull(repository);
        this.rules = Objects.requireNonNull(rules);
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
        String text = validDescription(description, folderName);
        String account = activeAccount();
        if (repository.findAll(account).stream().anyMatch(f -> f.name().equalsIgnoreCase(folderName))) {
            throw new IllegalArgumentException("folder '" + folderName + "' already exists");
        }

        var folder = new UserFolder(UUID.randomUUID().toString(), folderName, text);
        repository.save(account, folder);
        onFoldersChanged.run();
        return folder;
    }

    /** Only the definition goes: the mailbox folder and the mail in it are never touched. */
    @Override
    public synchronized void delete(String folderId) {
        String account = activeAccount();
        UserFolder folder = find(account, folderId);
        List<PrefilterRule> users = rules.findAll(account).stream()
                .filter(r -> folder.name().equalsIgnoreCase(r.folder()))
                .toList();
        if (!users.isEmpty()) {
            throw new FolderInUseException("Folder '" + folder.name() + "' is used by " + users.size()
                    + (users.size() == 1 ? " rule: " : " rules: ")
                    + users.stream().map(r -> r.field() + " " + r.pattern()).collect(Collectors.joining(", "))
                    + ". Delete those rules first.");
        }
        repository.deleteById(account, folder.id());
        onFoldersChanged.run();
    }

    @Override
    public synchronized UserFolder updateDescription(String folderId, String description) {
        String account = activeAccount();
        UserFolder folder = find(account, folderId);
        var updated = new UserFolder(folder.id(), folder.name(), validDescription(description, folder.name()));
        repository.save(account, updated);
        onFoldersChanged.run();
        return updated;
    }

    private UserFolder find(String account, String folderId) {
        String id = folderId == null ? "" : folderId.trim();
        return repository.findAll(account).stream()
                .filter(f -> f.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown folder: " + folderId));
    }

    private static String validDescription(String description, String folderName) {
        String text = description == null ? "" : description.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(
                    "description is required: say in one sentence what belongs in " + folderName);
        }
        if (text.length() > UserFolder.MAX_DESCRIPTION) {
            throw new IllegalArgumentException(
                    "description is longer than " + UserFolder.MAX_DESCRIPTION + " characters");
        }
        return text;
    }

    private String activeAccount() {
        return connections.current().map(MailProvider::accountId).orElseThrow(NotConnectedException::new);
    }
}
