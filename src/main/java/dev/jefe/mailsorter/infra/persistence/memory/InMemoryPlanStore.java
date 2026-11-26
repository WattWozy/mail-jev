package dev.jefe.mailsorter.infra.persistence.memory;

import dev.jefe.mailsorter.application.port.in.dto.Plan;
import dev.jefe.mailsorter.application.port.out.PlanStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryPlanStore implements PlanStore {
    private final Map<String, Plan> store = new ConcurrentHashMap<>();

    @Override
    public void save(Plan plan) {
        store.put(plan.id(), plan);
    }

    @Override
    public Optional<Plan> find(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public void remove(String id) {
        store.remove(id);
    }
}