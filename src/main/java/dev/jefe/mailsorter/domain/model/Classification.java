package dev.jefe.mailsorter.domain.model;

public record Classification(Category category, double confidence, String reason) {
    public Classification {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1]");
        }
    }
}
