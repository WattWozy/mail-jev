package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.StatsSnapshot;

import java.time.Instant;
import java.util.List;

public interface ActivityUseCase {
    /** Newest first; {@code since == null} means no lower time bound. */
    List<ActivityEntry> recent(Instant since, int limit);

    default List<ActivityEntry> recent(int limit) { return recent(null, limit); }
    StatsSnapshot stats();
}
