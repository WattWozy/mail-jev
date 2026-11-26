package dev.jefe.mailsorter.application.port.in.dto;

import dev.jefe.mailsorter.application.service.prefilter.rules.RuleField;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;

public record AddRuleCommand(
        RuleField field,
        String pattern,
        Action action,
        Category category
) {}
