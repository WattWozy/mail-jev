package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.port.in.ActivityUseCase;
import dev.jefe.mailsorter.application.port.out.ActivityRepository;
import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.StatsSnapshot;

import java.time.Instant;
import java.util.List;

/** Use case: read what has been sorted so far, for the connected account (all accounts when none is). */
public final class ActivityService implements ActivityUseCase {

    public static final int MAX_LIMIT = 500;

    private final ActivityRepository repository;
    private final ConnectionStore connections;

    public ActivityService(ActivityRepository repository, ConnectionStore connections) {
        this.repository = repository;
        this.connections = connections;
    }

    private String connectedAccount() {
        return connections.current().map(MailProvider::accountId).orElse(null);
    }

    @Override
    public List<ActivityEntry> recent(Instant since, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return repository.recent(connectedAccount(), since, Math.min(limit, MAX_LIMIT));
    }

    @Override
    public StatsSnapshot stats() {
        return repository.stats(connectedAccount());
    }
}
