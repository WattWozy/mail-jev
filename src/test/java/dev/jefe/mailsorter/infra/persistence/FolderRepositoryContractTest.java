package dev.jefe.mailsorter.infra.persistence;

import dev.jefe.mailsorter.application.port.out.FolderRepository;
import dev.jefe.mailsorter.domain.model.UserFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Same behaviour expected from every FolderRepository adapter. */
public abstract class FolderRepositoryContractTest {

    protected FolderRepository repo;

    protected abstract FolderRepository create();

    @BeforeEach
    void setUp() {
        repo = create();
    }

    @Test
    void isEmptyAtFirst() {
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void roundTripsFoldersInInsertionOrder() {
        var ikea = new UserFolder("a", "Ikea", "Anything from or about IKEA");
        var travel = new UserFolder("b", "Shops/Travel", "Flight and hotel deals");

        repo.save(ikea);
        repo.save(travel);

        assertThat(repo.findAll()).containsExactly(ikea, travel);
    }

    @Test
    void savingTheSameIdUpdatesIt() {
        repo.save(new UserFolder("a", "Ikea", "old"));
        repo.save(new UserFolder("a", "Ikea", "new"));

        assertThat(repo.findAll()).containsExactly(new UserFolder("a", "Ikea", "new"));
    }

    @Test
    void deletesById() {
        repo.save(new UserFolder("a", "Ikea", "x"));

        assertThat(repo.deleteById("a")).isTrue();
        assertThat(repo.deleteById("a")).isFalse();
        assertThat(repo.findAll()).isEmpty();
    }
}
