package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.domain.model.UserFolder;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryFolderStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FolderServiceTest {

    private final InMemoryFolderStore repository = new InMemoryFolderStore();
    private final List<List<UserFolder>> published = new ArrayList<>();
    private final FolderService service = new FolderService(repository, published::add);

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
    void everyChangeIsPublishedSoTheClassifierSeesIt() {
        var folder = service.add("Ikea", "IKEA offers");
        service.delete(folder.id());

        assertThat(published).containsExactly(List.of(folder), List.of());
    }

    @Test
    void reloadPublishesWhatIsStored() {
        repository.save(new UserFolder("f1", "Ikea", "IKEA offers"));

        service.reload();

        assertThat(published).containsExactly(List.of(new UserFolder("f1", "Ikea", "IKEA offers")));
    }
}
