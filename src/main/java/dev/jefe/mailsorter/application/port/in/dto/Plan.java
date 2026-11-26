package dev.jefe.mailsorter.application.port.in.dto;

import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Classification;

import java.util.List;

public record Plan(String id, List<Entry> entries) {
    public record Entry(String emailId, String from, String subject, Classification classification, Action action) {}
    public long countOf(Action a) { return entries.stream().filter(e -> e.action() == a).count(); }
}