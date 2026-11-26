package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.application.port.in.dto.AddRuleCommand;
import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;

public interface AddRuleUseCase {
    PrefilterRule execute(AddRuleCommand command);
}