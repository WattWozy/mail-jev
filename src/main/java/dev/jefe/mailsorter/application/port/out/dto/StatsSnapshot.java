package dev.jefe.mailsorter.application.port.out.dto;

public record StatsSnapshot(long totalSorted, long ruleHits, long jevHits, long manualOverrides) {}