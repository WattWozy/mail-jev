package dev.jefe.mailsorter.domain.model;

import java.util.Objects;

/**
 * @param folder the user folder this email belongs in, or null. When set, the policy files the email there
 *               instead of applying the category's action.
 */
public record Classification(Category category, double confidence, String reason, ClassificationSource source,
                             String folder) {
    public Classification {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1]");
        }
        Objects.requireNonNull(source, "source is required");
        folder = folder == null || folder.isBlank() ? null : folder.trim();
    }

    public Classification(Category category, double confidence, String reason, ClassificationSource source) {
        this(category, confidence, reason, source, null);
    }

    /** Model result by default; rules and fallbacks re-tag with {@link #from(ClassificationSource)}. */
    public Classification(Category category, double confidence, String reason) {
        this(category, confidence, reason, ClassificationSource.MODEL);
    }

    public Classification from(ClassificationSource newSource) {
        return newSource == source ? this : new Classification(category, confidence, reason, newSource, folder);
    }

    public Classification withFolder(String newFolder) {
        return new Classification(category, confidence, reason, source, newFolder);
    }
}
