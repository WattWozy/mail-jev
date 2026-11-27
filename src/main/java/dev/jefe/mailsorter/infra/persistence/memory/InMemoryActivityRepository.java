package dev.jefe.mailsorter.infra.persistence.memory;

import dev.jefe.mailsorter.application.port.out.ActivityRepository;
import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.StatsSnapshot;
import dev.jefe.mailsorter.domain.model.ClassificationSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class InMemoryActivityRepository implements ActivityRepository {

    private final List<ActivityEntry> entries = new ArrayList<>();

    @Override
    public synchronized void record(ActivityEntry entry) {
        entries.add(entry);
    }

    @Override
    public synchronized List<ActivityEntry> recent(String accountId, Instant since, int limit) {
        return entries.reversed().stream()               // stable sort: latest insert first on equal timestamps
                .filter(e -> accountId == null || accountId.equals(e.accountId()))
                .filter(e -> since == null || !e.timestamp().isBefore(since))
                .sorted(Comparator.comparing(ActivityEntry::timestamp).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized StatsSnapshot stats(String accountId) {
        List<ActivityEntry> scoped = entries.stream()
                .filter(e -> accountId == null || accountId.equals(e.accountId()))
                .toList();
        return new StatsSnapshot(
                scoped.size(),
                count(scoped, ClassificationSource.RULE),
                count(scoped, ClassificationSource.MODEL),
                scoped.stream().filter(ActivityEntry::overridden).count());
    }

    private static long count(List<ActivityEntry> scoped, ClassificationSource source) {
        return scoped.stream().filter(e -> e.source() == source).count();
    }
}
