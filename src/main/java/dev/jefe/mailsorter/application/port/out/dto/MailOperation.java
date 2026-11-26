package dev.jefe.mailsorter.application.port.out.dto;

import dev.jefe.mailsorter.domain.model.Action;

public record MailOperation(String emailId, Action action) {}
