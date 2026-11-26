package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.StatsSnapshot;

import java.util.List;

public interface ActivityRepository {
    void record(ActivityEntry entry);
    List<ActivityEntry> recent(int limit);
    StatsSnapshot stats();
}
