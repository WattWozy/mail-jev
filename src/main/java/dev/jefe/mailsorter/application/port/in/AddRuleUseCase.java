package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;
import dev.jefe.mailsorter.application.service.prefilter.rules.RuleField;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Category;

public interface AddRuleUseCase {
    PrefilterRule execute(AddRuleCommand command);
}