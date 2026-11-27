package dev.jefe.mailsorter.infra.persistence;

import dev.jefe.mailsorter.application.port.out.FolderRepository;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryFolderStore;

class InMemoryFolderStoreTest extends FolderRepositoryContractTest {
    @Override protected FolderRepository create() { return new InMemoryFolderStore(); }
}
