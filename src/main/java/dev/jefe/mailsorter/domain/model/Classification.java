package dev.jefe.mailsorter.domain.model;

import java.util.Objects;

/**
 * @param folder the user folder this email belongs in, or null. When set, the policy files the email there
 *               instead of applying any action.
 * @param action the action a user rule asked for, or null. When set (and no folder), it replaces the
 *               category's action. Never {@link Action#MOVE_TO_FOLDER}: that is expressed by {@code folder}.
 */
public record Classification(Category category, double confidence, String reason, ClassificationSource source,
                             String folder, Action action) {
    public Classification {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1]");
        }
        Objects.requireNonNull(source, "source is required");
        folder = folder == null || folder.isBlank() ? null : folder.trim();
        if (action == Action.MOVE_TO_FOLDER) {
            action = null;
        }
    }

    public Classification(Category category, double confidence, String reason, ClassificationSource source) {
        this(category, confidence, reason, source, null, null);
    }

    /** Model result by default; rules and fallbacks re-tag with {@link #from(ClassificationSource)}. */
    public Classification(Category category, double confidence, String reason) {
        this(category, confidence, reason, ClassificationSource.MODEL);
    }

    public Classification from(ClassificationSource newSource) {
        return newSource == source ? this
                : new Classification(category, confidence, reason, newSource, folder, action);
    }

    public Classification withFolder(String newFolder) {
        return new Classification(category, confidence, reason, source, newFolder, action);
    }

    public Classification withAction(Action newAction) {
        return new Classification(category, confidence, reason, source, folder, newAction);
    }
}
