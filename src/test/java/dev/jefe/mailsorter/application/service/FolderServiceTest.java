package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.domain.model.UserFolder;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryFolderStore;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryRuleStore;
import dev.jefe.mailsorter.application.exception.FolderInUseException;
import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;
import dev.jefe.mailsorter.application.service.prefilter.rules.RuleField;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private final InMemoryRuleStore rules = new InMemoryRuleStore();
    private final InMemoryConnectionStore connections = new InMemoryConnectionStore();
    private final AtomicInteger changes = new AtomicInteger();
    private final FolderService service = new FolderService(repository, rules, connections, changes::incrementAndGet);

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
        var disconnected = new FolderService(repository, rules, new InMemoryConnectionStore(), () -> {});

        assertThatThrownBy(disconnected::list).isInstanceOf(NotConnectedException.class);
    }

    // -- folders in use, editing, reserved names ----------------------------------------

    @Test
    void aFolderThatRulesStillUseCannotBeDeleted() {
        var folder = service.add("Ikea", "IKEA offers");
        rules.save(GMX, new PrefilterRule("r1", RuleField.FROM_DOMAIN, "ikea.com", Action.MOVE_TO_FOLDER,
                Category.OTHER, "Ikea"));

        assertThatThrownBy(() -> service.delete(folder.id()))
                .isInstanceOf(FolderInUseException.class)
                .hasMessageContaining("1 rule")
                .hasMessageContaining("ikea.com");
        assertThat(service.list()).containsExactly(folder);
    }

    @Test
    void anotherAccountsRulesDoNotBlockTheDelete() {
        var folder = service.add("Ikea", "IKEA offers");
        rules.save(GMAIL, new PrefilterRule("r1", RuleField.FROM_DOMAIN, "ikea.com", Action.MOVE_TO_FOLDER,
                Category.OTHER, "Ikea"));

        service.delete(folder.id());

        assertThat(service.list()).isEmpty();
    }

    @Test
    void theDescriptionCanBeEdited() {
        var folder = service.add("Ikea", "IKEA offers");

        var edited = service.updateDescription(folder.id(), "  Anything from or about IKEA  ");

        assertThat(edited).isEqualTo(new UserFolder(folder.id(), "Ikea", "Anything from or about IKEA"));
        assertThat(service.list()).containsExactly(edited);
        assertThat(changes).hasValue(2);
    }

    @Test
    void anEditedDescriptionIsValidatedToo() {
        var folder = service.add("Ikea", "IKEA offers");

        assertThatThrownBy(() -> service.updateDescription(folder.id(), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateDescription("nope", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anotherAccountsFolderCannotBeEdited() {
        var folder = service.add("Ikea", "IKEA offers");
        connections.save(new FakeMailProvider(GMAIL));

        assertThatThrownBy(() -> service.updateDescription(folder.id(), "hijacked"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Archive", "archive", "Trash", "TRASH", "mail-sorter", "mail-sorter/review", "Mail-Sorter/x"})
    void theAppsOwnFoldersAreReserved(String name) {
        assertThatThrownBy(() -> service.add(name, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }
}
