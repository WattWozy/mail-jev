package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.application.port.out.dto.AutopilotSettings;

import java.util.Optional;

/** Driven port. Persists autopilot configuration so it survives restarts. */
public interface AutopilotSettingsStore {
    Optional<AutopilotSettings> load();
    void save(AutopilotSettings settings);
}
