package dev.jefe.mailsorter.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Pure domain logic: maps a classification to an action. No I/O. */
public final class PolicyEngine {

    /** Categories that must never be destroyed, whatever the config says. */
    private static final Set<Category> PROTECTED = EnumSet.of(Category.INVOICE, Category.PERSONAL);

    private final Map<Category, Action> actions;
    private final double threshold;

    public PolicyEngine(Map<Category, Action> actions, double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be in [0,1]");
        }
        this.actions = Map.copyOf(actions);
        this.threshold = threshold;
    }

    public Action decide(Classification c) {
        if (c.confidence() < threshold) {
            return Action.FLAG_FOR_REVIEW;
        }
        Action configured = actions.getOrDefault(c.category(), Action.KEEP);
        if (PROTECTED.contains(c.category()) && configured == Action.MOVE_TO_TRASH) {
            return Action.KEEP;
        }
        return configured;
    }
}
