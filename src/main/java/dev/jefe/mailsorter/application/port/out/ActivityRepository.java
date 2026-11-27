package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.StatsSnapshot;

import java.time.Instant;
import java.util.List;

public interface ActivityRepository {
    void record(ActivityEntry entry);

    /**
     * Newest first, for one account ("service|username"); {@code accountId == null} means all accounts.
     * {@code since == null} means no lower time bound; otherwise only entries at or after it.
     */
    List<ActivityEntry> recent(String accountId, Instant since, int limit);

    default List<ActivityEntry> recent(String accountId, int limit) { return recent(accountId, null, limit); }

    /** For one account; {@code accountId == null} means all accounts. */
    StatsSnapshot stats(String accountId);

    default List<ActivityEntry> recent(int limit) { return recent(null, limit); }

    default StatsSnapshot stats() { return stats(null); }
}
