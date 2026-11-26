package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.StatsSnapshot;

import java.util.List;

public interface ActivityUseCase {
    List<ActivityEntry> recent(int limit);
    StatsSnapshot stats();
}
