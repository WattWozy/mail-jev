package dev.jefe.mailsorter.domain.model;

import java.util.Objects;

public record Classification(Category category, double confidence, String reason, ClassificationSource source) {
    public Classification {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1]");
        }
        Objects.requireNonNull(source, "source is required");
    }

    /** Model result by default; rules and fallbacks re-tag with {@link #from(ClassificationSource)}. */
    public Classification(Category category, double confidence, String reason) {
        this(category, confidence, reason, ClassificationSource.MODEL);
    }

    public Classification from(ClassificationSource newSource) {
        return newSource == source ? this : new Classification(category, confidence, reason, newSource);
    }
}
