package dev.jefe.mailsorter.infra.persistence;

import dev.jefe.mailsorter.application.port.out.ActivityRepository;
import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.StatsSnapshot;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.ClassificationSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Same behaviour expected from every ActivityRepository adapter. */
public abstract class ActivityRepositoryContractTest {

    private static final Instant T0 = Instant.parse("2026-11-26T20:00:00Z");

    protected ActivityRepository repo;

    protected abstract ActivityRepository create();

    @BeforeEach
    void setUp() {
        repo = create();
    }

    private static ActivityEntry entry(String id, int secondsAfterT0, ClassificationSource source, boolean overridden) {
        return new ActivityEntry(T0.plusSeconds(secondsAfterT0), "gmx|me@gmx.com", id, "news@shop.com",
                "subject " + id, Category.NEWSLETTER, 0.97, "reason " + id, Action.ARCHIVE, source, overridden);
    }

    @Test
    void isEmptyAtFirst() {
        assertThat(repo.recent(10)).isEmpty();
        assertThat(repo.stats()).isEqualTo(new StatsSnapshot(0, 0, 0, 0));
    }

    @Test
    void roundTripsAnEntry() {
        var e = entry("1", 0, ClassificationSource.RULE, true);

        repo.record(e);

        assertThat(repo.recent(10)).containsExactly(e);
    }

    @Test
    void recentReturnsNewestFirstAndRespectsTheLimit() {
        repo.record(entry("old", 0, ClassificationSource.MODEL, false));
        repo.record(entry("mid", 10, ClassificationSource.MODEL, false));
        repo.record(entry("new", 20, ClassificationSource.MODEL, false));

        assertThat(repo.recent(2)).extracting(ActivityEntry::emailId).containsExactly("new", "mid");
    }

    @Test
    void statsCountTotalsBySourceAndOverrides() {
        repo.record(entry("1", 0, ClassificationSource.RULE, false));
        repo.record(entry("2", 1, ClassificationSource.RULE, true));
        repo.record(entry("3", 2, ClassificationSource.MODEL, false));
        repo.record(entry("4", 3, ClassificationSource.FALLBACK, false));

        assertThat(repo.stats()).isEqualTo(new StatsSnapshot(4, 2, 1, 1));
    }
}
