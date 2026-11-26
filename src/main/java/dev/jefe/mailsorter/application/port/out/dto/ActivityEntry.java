package dev.jefe.mailsorter.application.port.out.dto;

import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.ClassificationSource;

import java.time.Instant;

/** One email that was actually acted on (manually or by autopilot). */
public record ActivityEntry(
        Instant timestamp,
        String accountId,
        String emailId,
        String from,
        String subject,
        Category category,
        double confidence,
        String reason,
        Action action,              // the action really performed (after overrides)
        ClassificationSource source,
        boolean overridden          // the user replaced the recommended action
) {}
