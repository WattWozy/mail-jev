package dev.jefe.mailsorter.infra.persistence;

import dev.jefe.mailsorter.application.port.out.FolderRepository;
import dev.jefe.mailsorter.domain.model.UserFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Same behaviour expected from every FolderRepository adapter. */
public abstract class FolderRepositoryContractTest {

    private static final String GMX = "gmx|me@gmx.com";
    private static final String GMAIL = "gmail|me@gmail.com";

    protected FolderRepository repo;

    protected abstract FolderRepository create();

    @BeforeEach
    void setUp() {
        repo = create();
    }

    @Test
    void isEmptyAtFirst() {
        assertThat(repo.findAll(GMX)).isEmpty();
    }

    @Test
    void roundTripsFoldersInInsertionOrder() {
        var ikea = new UserFolder("a", "Ikea", "Anything from or about IKEA");
        var travel = new UserFolder("b", "Shops/Travel", "Flight and hotel deals");

        repo.save(GMX, ikea);
        repo.save(GMX, travel);

        assertThat(repo.findAll(GMX)).containsExactly(ikea, travel);
    }

    @Test
    void savingTheSameIdUpdatesIt() {
        repo.save(GMX, new UserFolder("a", "Ikea", "old"));
        repo.save(GMX, new UserFolder("a", "Ikea", "new"));

        assertThat(repo.findAll(GMX)).containsExactly(new UserFolder("a", "Ikea", "new"));
    }

    @Test
    void deletesById() {
        repo.save(GMX, new UserFolder("a", "Ikea", "x"));

        assertThat(repo.deleteById(GMX, "a")).isTrue();
        assertThat(repo.deleteById(GMX, "a")).isFalse();
        assertThat(repo.findAll(GMX)).isEmpty();
    }

    @Test
    void accountsDoNotSeeEachOthersFolders() {
        repo.save(GMAIL, new UserFolder("b", "Ikea", "y"));
        repo.save(GMX, new UserFolder("a", "Ikea", "x"));

        assertThat(repo.findAll(GMX)).containsExactly(new UserFolder("a", "Ikea", "x"));
        assertThat(repo.findAll(GMAIL)).containsExactly(new UserFolder("b", "Ikea", "y"));
    }

    @Test
    void deletingIsScopedToTheAccount() {
        repo.save(GMX, new UserFolder("a", "Ikea", "x"));

        assertThat(repo.deleteById(GMAIL, "a")).isFalse();
        assertThat(repo.findAll(GMX)).hasSize(1);
    }
}
