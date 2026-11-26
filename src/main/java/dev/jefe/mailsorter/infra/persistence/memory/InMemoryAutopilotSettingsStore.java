package dev.jefe.mailsorter.infra.persistence.memory;

import dev.jefe.mailsorter.application.port.out.AutopilotSettingsStore;
import dev.jefe.mailsorter.application.port.out.dto.AutopilotSettings;

import java.util.Optional;

public final class InMemoryAutopilotSettingsStore implements AutopilotSettingsStore {
    private volatile AutopilotSettings settings;
    @Override public Optional<AutopilotSettings> load() { return Optional.ofNullable(settings); }
    @Override public void save(AutopilotSettings settings) { this.settings = settings; }
}
