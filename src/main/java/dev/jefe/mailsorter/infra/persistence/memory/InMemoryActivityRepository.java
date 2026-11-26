package dev.jefe.mailsorter.infra.persistence.memory;

import dev.jefe.mailsorter.application.port.out.ActivityRepository;
import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.StatsSnapshot;
import dev.jefe.mailsorter.domain.model.ClassificationSource;

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
    public synchronized List<ActivityEntry> recent(int limit) {
        return entries.reversed().stream()               // stable sort: latest insert first on equal timestamps
                .sorted(Comparator.comparing(ActivityEntry::timestamp).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized StatsSnapshot stats() {
        return new StatsSnapshot(
                entries.size(),
                count(ClassificationSource.RULE),
                count(ClassificationSource.MODEL),
                entries.stream().filter(ActivityEntry::overridden).count());
    }

    private long count(ClassificationSource source) {
        return entries.stream().filter(e -> e.source() == source).count();
    }
}
