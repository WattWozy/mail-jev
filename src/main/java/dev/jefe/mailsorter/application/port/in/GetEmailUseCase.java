package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.domain.model.EmailContent;

public interface GetEmailUseCase {
    public EmailContent execute(String emailId);
}
