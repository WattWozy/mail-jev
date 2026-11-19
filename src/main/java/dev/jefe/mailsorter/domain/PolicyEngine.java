package dev.jefe.mailsorter.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Pure domain logic: maps a classification to an action. No I/O. */
public final class PolicyEngine {

    /** Categories that must never be destroyed, whatever the config says. */
    private static final Set<Category> PROTECTED = EnumSet.of(Category.INVOICE, Category.PERSONAL);

    private final Map<Category, Action> actions;
    private final Map<Action, Double> actionThresholds;
    private final double defaultThreshold;

    public PolicyEngine(Map<Category, Action> actions, double defaultThreshold) {
        this(actions, defaultThresholds(defaultThreshold), defaultThreshold);
    }

    public PolicyEngine(
            Map<Category, Action> actions,
            Map<Action, Double> actionThresholds,
            double defaultThreshold) {
        validateThreshold(defaultThreshold, "defaultThreshold");
        actionThresholds.forEach((action, t) -> validateThreshold(t, "threshold for " + action));

        this.actions = Map.copyOf(actions);
        this.actionThresholds = Map.copyOf(actionThresholds);
        this.defaultThreshold = defaultThreshold;
    }

    public Action decide(Classification c) {
        if (c.category() == Category.UNKNOWN) {
            return Action.FLAG_FOR_REVIEW;
        }

        Action configured = actions.getOrDefault(c.category(), Action.KEEP);
        if (PROTECTED.contains(c.category()) && configured == Action.MOVE_TO_TRASH) {
            configured = Action.KEEP;
        }

        double required = actionThresholds.getOrDefault(configured, defaultThreshold);
        if (c.confidence() < required) {
            return Action.FLAG_FOR_REVIEW;
        }

        return configured;
    }

    private static Map<Action, Double> defaultThresholds(double fallback) {
        Map<Action, Double> defaults = new EnumMap<>(Action.class);
        defaults.put(Action.KEEP, 0.50);
        defaults.put(Action.FLAG_FOR_REVIEW, 0.0);
        defaults.put(Action.ARCHIVE, fallback); // e.g. 0.65 - 0.70
        defaults.put(Action.MOVE_TO_TRASH, Math.max(fallback, 0.85));
        return defaults;
    }

    private static void validateThreshold(double value, String label) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(label + " must be in [0,1]");
        }
    }
}