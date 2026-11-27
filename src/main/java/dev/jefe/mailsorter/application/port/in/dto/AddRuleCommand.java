package dev.jefe.mailsorter.application.port.in.dto;

import dev.jefe.mailsorter.application.service.prefilter.rules.RuleField;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;

/** @param folder optional user folder; when set the rule files matching mail there and {@code action} is ignored. */
public record AddRuleCommand(
        RuleField field,
        String pattern,
        Action action,
        Category category,
        String folder
) {
    public AddRuleCommand(RuleField field, String pattern, Action action, Category category) {
        this(field, pattern, action, category, null);
    }
}
