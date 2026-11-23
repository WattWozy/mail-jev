package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.application.service.prefilter.rules.RuleField;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Category;

public record AddRuleCommand(
        RuleField field,
        String pattern,
        Action action,
        Category category
) {}
