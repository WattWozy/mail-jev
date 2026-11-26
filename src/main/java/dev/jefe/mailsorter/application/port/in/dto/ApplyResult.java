package dev.jefe.mailsorter.application.port.in.dto;

import dev.jefe.mailsorter.domain.model.Action;

import java.util.List;
import java.util.Map;

public record ApplyResult(Map<Action, Long> applied, List<Failure> failures) {
    public record Failure(String emailId, String message) {}
}
