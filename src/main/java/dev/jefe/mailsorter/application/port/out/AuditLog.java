package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Classification;

/** Driven port. Every action is recorded so it can be undone. */
public interface AuditLog {
    void record(String emailId, Classification classification, Action action, boolean overridden);
}