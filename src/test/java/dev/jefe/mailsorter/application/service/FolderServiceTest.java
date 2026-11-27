package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.domain.model.UserFolder;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryFolderStore;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryConnectionStore;
import dev.jefe.mailsorter.testsupport.FakeMailProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FolderServiceTest {

    private static final String GMX = "gmx|me@gmx.com";
    private static final String GMAIL = "gmail|me@gmail.com";

    private final InMemoryFolderStore repository = new InMemoryFolderStore();
    private final InMemoryConnectionStore connections = new InMemoryConnectionStore();
    private final AtomicInteger changes = new AtomicInteger();
    private final FolderService service = new FolderService(repository, connections, changes::incrementAndGet);

    @BeforeEach
    void connectGmx() {
        connections.save(new FakeMailProvider(GMX));
    }

    @Test
    void addsAFolderWithItsDescription() {
        var folder = service.add(" Travel promotions ", "  Flight, hotel and holiday deals  ");

        assertThat(folder.id()).isNotBlank();
        assertThat(folder.name()).isEqualTo("Travel promotions");
        assertThat(folder.description()).isEqualTo("Flight, hotel and holiday deals");
        assertThat(service.list()).containsExactly(folder);
    }

    @Test
    void aDescriptionIsRequiredBecauseJevSortsByIt() {
        assertThatThrownBy(() -> service.add("Ikea", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
        assertThat(service.list()).isEmpty();
    }

    @Test
    void anOverlyLongDescriptionIsRejected() {
        assertThatThrownBy(() -> service.add("Ikea", "x".repeat(UserFolder.MAX_DESCRIPTION + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsafeFolderNamesAreRejected() {
        assertThatThrownBy(() -> service.add("INBOX", "everything"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add(" ", "everything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void namesAreUniqueIgnoringCase() {
        service.add("Ikea", "IKEA offers");

        assertThatThrownBy(() -> service.add("ikea", "again"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exists");
    }

    @Test
    void deletesAFolder() {
        var folder = service.add("Ikea", "IKEA offers");

        service.delete(folder.id());

        assertThat(service.list()).isEmpty();
    }

    @Test
    void deletingAnUnknownFolderIsRejected() {
        assertThatThrownBy(() -> service.delete("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyChangeIsAnnouncedSoTheClassifierSeesIt() {
        var folder = service.add("Ikea", "IKEA offers");
        service.delete(folder.id());

        assertThat(changes).hasValue(2);
    }

    // -- per account ----------------------------------------------------------------

    @Test
    void foldersBelongToTheConnectedAccount() {
        var folder = service.add("Ikea", "IKEA offers");

        connections.save(new FakeMailProvider(GMAIL));

        assertThat(service.list()).isEmpty();
        assertThat(service.foldersFor(GMX)).containsExactly(folder);
    }

    @Test
    void twoAccountsCanEachHaveAFolderWithTheSameName() {
        service.add("Ikea", "IKEA offers");
        connections.save(new FakeMailProvider(GMAIL));

        var other = service.add("Ikea", "IKEA orders");

        assertThat(service.list()).containsExactly(other);
    }

    @Test
    void anotherAccountsFolderCannotBeDeleted() {
        var folder = service.add("Ikea", "IKEA offers");
        connections.save(new FakeMailProvider(GMAIL));

        assertThatThrownBy(() -> service.delete(folder.id())).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.foldersFor(GMX)).containsExactly(folder);
    }

    @Test
    void managingFoldersNeedsAConnectedAccount() {
        var disconnected = new FolderService(repository, new InMemoryConnectionStore(), () -> {});

        assertThatThrownBy(disconnected::list).isInstanceOf(NotConnectedException.class);
    }
}
