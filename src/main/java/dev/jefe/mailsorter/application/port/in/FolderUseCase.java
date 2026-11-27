package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.domain.model.UserFolder;

import java.util.List;

public interface FolderUseCase {
    List<UserFolder> list();
    UserFolder add(String name, String description);
    void delete(String folderId);
    UserFolder updateDescription(String folderId, String description);
}
