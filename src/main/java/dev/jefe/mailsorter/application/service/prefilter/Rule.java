package dev.jefe.mailsorter.application.service.prefilter;

import dev.jefe.mailsorter.domain.model.Classification;
import dev.jefe.mailsorter.domain.model.Email;

import java.util.Optional;

public interface Rule {
    Optional<Classification> apply(Email email);
}