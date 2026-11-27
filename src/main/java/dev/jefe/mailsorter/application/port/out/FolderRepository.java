package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.domain.model.UserFolder;

import java.util.List;

/** Driven port. Folders belong to one account ("service|username") and keep their creation order. */
public interface FolderRepository {
    List<UserFolder> findAll(String accountId);
    void save(String accountId, UserFolder folder);
    boolean deleteById(String accountId, String id);
}
