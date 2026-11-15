package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Classification;
import java.util.List;

public record ClassificationReport(List<Entry> entries, boolean dryRun) {

    public record Entry(String emailId, Classification classification, Action action) {}

    public long countOf(Action action) {
        return entries.stream().filter(e -> e.action() == action).count();
    }
}
