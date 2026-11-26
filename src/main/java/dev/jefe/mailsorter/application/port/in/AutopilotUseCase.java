package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.application.port.in.dto.AutopilotStatus;

public interface AutopilotUseCase {
    AutopilotStatus getStatus();
    AutopilotStatus setEnabled(boolean enabled, int intervalSeconds, int limit);
    /** Runs one classify-and-apply batch now. No-op if a run is already in progress. */
    AutopilotStatus runNow();
    /** Called on startup: restarts the schedule if autopilot was left enabled. */
    void resumeIfEnabled();
}
