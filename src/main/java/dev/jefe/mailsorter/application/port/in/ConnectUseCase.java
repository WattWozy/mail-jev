package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.domain.config.MailboxConfig;

public interface ConnectUseCase {
    void execute(MailboxConfig config);
}
