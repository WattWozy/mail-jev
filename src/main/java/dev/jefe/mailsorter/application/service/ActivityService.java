package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.port.in.ActivityUseCase;
import dev.jefe.mailsorter.application.port.out.ActivityRepository;
import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.StatsSnapshot;

import java.util.List;

/** Use case: read what has been sorted so far. */
public final class ActivityService implements ActivityUseCase {

    public static final int MAX_LIMIT = 500;

    private final ActivityRepository repository;

    public ActivityService(ActivityRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ActivityEntry> recent(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return repository.recent(Math.min(limit, MAX_LIMIT));
    }

    @Override
    public StatsSnapshot stats() {
        return repository.stats();
    }
}
