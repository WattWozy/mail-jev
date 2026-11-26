package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.application.port.in.dto.Plan;

import java.util.Optional;

public interface PlanStore {
    void save(Plan plan);
    Optional<Plan> find(String id);
    void remove(String id);
}