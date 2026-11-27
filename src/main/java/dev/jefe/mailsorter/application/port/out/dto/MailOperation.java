package dev.jefe.mailsorter.application.port.out.dto;

import dev.jefe.mailsorter.domain.model.Action;

/** @param folder destination for {@link Action#MOVE_TO_FOLDER}; always null for any other action. */
public record MailOperation(String emailId, Action action, String folder) {
    public MailOperation {
        if (action == Action.MOVE_TO_FOLDER) {
            if (folder == null || folder.isBlank()) {
                throw new IllegalArgumentException("MOVE_TO_FOLDER needs a folder (email " + emailId + ")");
            }
        } else {
            folder = null;
        }
    }

    public MailOperation(String emailId, Action action) {
        this(emailId, action, null);
    }
}
