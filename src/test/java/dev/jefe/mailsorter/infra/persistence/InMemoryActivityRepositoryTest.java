package dev.jefe.mailsorter.infra.persistence;

import dev.jefe.mailsorter.application.port.out.ActivityRepository;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryActivityRepository;

class InMemoryActivityRepositoryTest extends ActivityRepositoryContractTest {
    @Override protected ActivityRepository create() { return new InMemoryActivityRepository(); }
}
