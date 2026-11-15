package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.domain.Email;
import java.util.List;

/** Driven port. Adapters: IMAP, Gmail API, Microsoft Graph. */
public interface MailProvider {
    List<Email> listUnprocessed(int limit);
    void markProcessed(String emailId);
    void archive(String emailId);
    void moveToTrash(String emailId);
    void restoreToInbox(String emailId);
    void addLabel(String emailId, String label);
    void removeLabel(String emailId, String label);
}