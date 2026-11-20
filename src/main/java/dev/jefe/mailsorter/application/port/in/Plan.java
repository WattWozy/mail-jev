package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Classification;

import java.util.List;

public record Plan(String id, List<Entry> entries) {
    public record Entry(String emailId, String from, String subject, Classification classification, Action action) {}
    public long countOf(Action a) { return entries.stream().filter(e -> e.action() == a).count(); }
}