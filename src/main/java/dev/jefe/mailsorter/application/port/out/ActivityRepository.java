package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.StatsSnapshot;

import java.util.List;

public interface ActivityRepository {
    void record(ActivityEntry entry);

    /** Newest first, for one account ("service|username"); {@code accountId == null} means all accounts. */
    List<ActivityEntry> recent(String accountId, int limit);

    /** For one account; {@code accountId == null} means all accounts. */
    StatsSnapshot stats(String accountId);

    default List<ActivityEntry> recent(int limit) { return recent(null, limit); }

    default StatsSnapshot stats() { return stats(null); }
}
