package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.domain.MailboxConfig;

public interface ConnectUseCase {
    void execute(MailboxConfig config);
}
