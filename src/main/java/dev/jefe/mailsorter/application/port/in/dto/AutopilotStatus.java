package dev.jefe.mailsorter.application.port.in.dto;

public record AutopilotStatus(boolean enabled, int intervalSeconds, int limit) {}