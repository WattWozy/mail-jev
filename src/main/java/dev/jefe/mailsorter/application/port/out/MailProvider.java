package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.application.port.out.dto.MailOperation;
import dev.jefe.mailsorter.domain.model.Email;
import dev.jefe.mailsorter.domain.model.EmailContent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Driven port. Adapters: IMAP, Gmail API, Microsoft Graph. */
public interface MailProvider extends AutoCloseable {
    EmailContent read(String emailId);
    List<Email> listUnprocessed(int limit);
    /** Applies all operations in as few round trips as possible. Returns emailId -> reason for each failure. */
    Map<String, String> applyAll(List<MailOperation> operations, String reviewLabel);
    String accountId();
    void close();
    void markProcessed(String emailId);
    void archive(String emailId);
    void moveToTrash(String emailId);
    void restoreToInbox(String emailId);
    void addLabel(String emailId, String label);
    void removeLabel(String emailId, String label);
}