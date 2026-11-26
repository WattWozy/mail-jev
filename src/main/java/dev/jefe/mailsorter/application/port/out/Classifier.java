package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.domain.model.Classification;
import dev.jefe.mailsorter.domain.model.Email;

/** Driven port. The "jev" adapter implements this. */
public interface Classifier {
    Classification classify(Email email);
}
