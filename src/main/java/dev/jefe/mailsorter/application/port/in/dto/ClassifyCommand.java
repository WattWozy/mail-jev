package dev.jefe.mailsorter.application.port.in.dto;

public record ClassifyCommand(int limit) {
    public ClassifyCommand { if (limit <= 0) throw new IllegalArgumentException("limit must be positive"); }
}
