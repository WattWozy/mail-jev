package dev.jefe.mailsorter.infra.memory.inMemory;

import dev.jefe.mailsorter.application.port.out.AuditLog;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Classification;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryAuditLog implements AuditLog {
    public record Entry(String emailId, Classification classification, Action action, boolean dryRun) {}
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    @Override public void record(String id, Classification c, Action a, boolean dryRun) {
        entries.add(new Entry(id, c, a, dryRun));
    }
    public List<Entry> entries() { return List.copyOf(entries); }
}
