package dev.jefe.mailsorter.application.port.in.dto;

import java.time.Instant;

/** {@code lastRunAt}, {@code lastResult} and {@code lastError} are null until the first run. */
public record AutopilotStatus(
        boolean enabled,
        int intervalSeconds,
        int limit,
        Instant lastRunAt,
        String lastResult,
        String lastError
) {}
