package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.domain.model.UserFolder;

import java.util.List;

/** Driven port. User folders in the order they were created. */
public interface FolderRepository {
    List<UserFolder> findAll();
    void save(UserFolder folder);
    boolean deleteById(String id);
}
