package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;

import java.util.List;

/** Driven port. Rules belong to one account ("service|username") and keep their creation order. */
public interface RuleRepository {
    List<PrefilterRule> findAll(String accountId);
    void save(String accountId, PrefilterRule rule);
    boolean deleteById(String accountId, String id);
}
