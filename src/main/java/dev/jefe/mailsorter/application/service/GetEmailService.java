package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.GetEmailUseCase;
import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.domain.EmailContent;

public class GetEmailService implements GetEmailUseCase {
    private final ConnectionStore store;

    public GetEmailService(ConnectionStore store) {
        this.store = store;
    }

    @Override
    public EmailContent execute(String emailId) {
        if (emailId == null || emailId.isBlank()) {
            throw new IllegalArgumentException("emailId is required");
        }

        MailProvider mail = store.current()          // ← same method the other services use
                .orElseThrow(NotConnectedException::new);

        return mail.read(emailId);
    }
}

