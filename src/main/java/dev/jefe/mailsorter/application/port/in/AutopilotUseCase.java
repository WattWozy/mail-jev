package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.application.port.in.dto.AutopilotStatus;

public interface AutopilotUseCase {
    AutopilotStatus getStatus();
    AutopilotStatus setEnabled(boolean enabled, int intervalSeconds, int limit);
}