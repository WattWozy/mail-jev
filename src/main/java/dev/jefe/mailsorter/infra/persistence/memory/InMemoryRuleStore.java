package dev.jefe.mailsorter.infra.persistence.memory;
import dev.jefe.mailsorter.application.port.out.RuleRepository;
import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;


import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryRuleStore implements RuleRepository {

    private final ConcurrentHashMap<String, PrefilterRule> byId = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();

    @Override
    public List<PrefilterRule> findAll() {
        return order.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public void save(PrefilterRule rule) {
        Objects.requireNonNull(rule, "rule is required");
        boolean isNew = byId.put(rule.id(), rule) == null;
        if (isNew) {
            order.add(rule.id());
        }
    }

    @Override
    public boolean deleteById(String id) {
        if (id == null) return false;
        order.remove(id);
        return byId.remove(id) != null;
    }
}