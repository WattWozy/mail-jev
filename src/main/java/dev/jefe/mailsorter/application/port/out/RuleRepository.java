package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;

import java.util.List;

public interface RuleRepository {
    List<PrefilterRule> findAll();
    void save(PrefilterRule rule);
    boolean deleteById(String id);
}
