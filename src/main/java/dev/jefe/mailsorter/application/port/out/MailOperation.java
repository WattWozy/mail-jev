package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.domain.Action;

public record MailOperation(String emailId, Action action) {}
