package dev.jefe.mailsorter.application.port.out.dto;

import dev.jefe.mailsorter.domain.model.Action;

import java.time.Instant;

public record ActivityEntry(
        String emailId,
        String from,
        String subject,
        Action action,
        String category,
        String reason,
        String source,          // "RULE" | "JEV" | "MANUAL"
        Instant timestamp
) {}
