package dev.jefe.mailsorter.infra.persistence.memory;

import dev.jefe.mailsorter.application.port.out.RuleRepository;
import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryRuleStore implements RuleRepository {

    private final Map<String, Map<String, PrefilterRule>> byAccount = new HashMap<>(); // inner: insertion order

    @Override
    public synchronized List<PrefilterRule> findAll(String accountId) {
        return List.copyOf(byAccount.getOrDefault(accountId, Map.of()).values());
    }

    @Override
    public synchronized void save(String accountId, PrefilterRule rule) {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(rule, "rule is required");
        byAccount.computeIfAbsent(accountId, k -> new LinkedHashMap<>()).put(rule.id(), rule);
    }

    @Override
    public synchronized boolean deleteById(String accountId, String id) {
        var rules = byAccount.get(accountId);
        return rules != null && id != null && rules.remove(id) != null;
    }
}
