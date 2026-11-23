package dev.jefe.mailsorter.application.port.in;

import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;

import java.util.List;

public interface ListRulesUseCase {
    List<PrefilterRule> execute();
}