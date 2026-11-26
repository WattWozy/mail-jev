package dev.jefe.mailsorter.application.port.in.dto;

import dev.jefe.mailsorter.domain.model.Action;

import java.util.Map;

public record ApplyCommand(String planId, Map<String, Action> overrides) {
    public ApplyCommand { overrides = Map.copyOf(overrides); }
    public static ApplyCommand of(String planId) { return new ApplyCommand(planId, Map.of()); }
}
