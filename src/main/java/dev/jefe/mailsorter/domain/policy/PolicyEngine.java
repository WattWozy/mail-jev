package dev.jefe.mailsorter.domain.policy;

import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.Classification;

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
        // What the user asked for beats the category: a folder first (filing is never destructive, so protected
        // categories may be filed too), then a rule's action (still never trashing a protected category).
        Action configured;
        if (c.folder() != null) {
            configured = Action.MOVE_TO_FOLDER;
        } else if (c.action() != null) {
            configured = protect(c.category(), c.action());
        } else {
            configured = actionFor(c);
        }
        if (configured == Action.FLAG_FOR_REVIEW) {
            return configured;
        }

        double required = actionThresholds.getOrDefault(configured, defaultThreshold);
        if (c.confidence() < required) {
            return Action.FLAG_FOR_REVIEW;
        }

        return configured;
    }

    private Action actionFor(Classification c) {
        if (c.category() == Category.UNKNOWN) {
            return Action.FLAG_FOR_REVIEW;
        }

        return protect(c.category(), actions.getOrDefault(c.category(), Action.KEEP));
    }

    private static Action protect(Category category, Action action) {
        return PROTECTED.contains(category) && action == Action.MOVE_TO_TRASH ? Action.KEEP : action;
    }

    private static Map<Action, Double> defaultThresholds(double fallback) {
        Map<Action, Double> defaults = new EnumMap<>(Action.class);
        defaults.put(Action.KEEP, 0.50);
        defaults.put(Action.FLAG_FOR_REVIEW, 0.0);
        defaults.put(Action.ARCHIVE, fallback); // e.g. 0.65 - 0.70
        // filing deletes nothing and is easy to undo, so it needs less certainty than archiving or trashing
        defaults.put(Action.MOVE_TO_FOLDER, Math.min(fallback, 0.70));
        defaults.put(Action.MOVE_TO_TRASH, Math.max(fallback, 0.85));
        return defaults;
    }

    private static void validateThreshold(double value, String label) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(label + " must be in [0,1]");
        }
    }
}