package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.port.in.dto.AddRuleCommand;
import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;
import dev.jefe.mailsorter.application.service.prefilter.rules.RuleField;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.Email;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryRuleStore;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryConnectionStore;
import dev.jefe.mailsorter.testsupport.FakeMailProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleServiceTest {

    private static final String GMX = "gmx|me@gmx.com";
    private static final String GMAIL = "gmail|me@gmail.com";

    private final InMemoryRuleStore repository = new InMemoryRuleStore();
    private final InMemoryConnectionStore connections = new InMemoryConnectionStore();
    private final AtomicInteger changes = new AtomicInteger();
    private final RuleService service = new RuleService(repository, connections, changes::incrementAndGet);

    @BeforeEach
    void connectGmx() {
        connections.save(new FakeMailProvider(GMX));
    }

    private static Email from(String sender) {
        return new Email("1", sender, "Your new sofa", "snippet", Map.of());
    }

    @Test
    void aFolderRuleFilesMatchingMailIntoThatFolder() {
        var rule = service.execute(new AddRuleCommand(RuleField.FROM_DOMAIN, "ikea.com", null, null, "Ikea"));

        var classification = rule.apply(from("Ikea <news@email.ikea.com>")).orElseThrow();

        assertThat(rule.action()).isEqualTo(Action.MOVE_TO_FOLDER);
        assertThat(classification.folder()).isEqualTo("Ikea");
        assertThat(classification.confidence()).isEqualTo(1.0);
    }

    @Test
    void theFolderNameKeepsTheUsersSpellingWhileThePatternIsNormalized() {
        var rule = service.execute(new AddRuleCommand(
                RuleField.SUBJECT_CONTAINS, "  Flight DEAL ", null, Category.PROMOTION, "  Travel Promotions "));

        assertThat(rule.pattern()).isEqualTo("flight deal");
        assertThat(rule.folder()).isEqualTo("Travel Promotions");
        assertThat(rule.category()).isEqualTo(Category.PROMOTION);
    }

    @Test
    void aFolderRuleIsAlwaysAMoveToFolderWhateverActionWasSent() {
        var rule = service.execute(new AddRuleCommand(RuleField.FROM_DOMAIN, "ikea.com", Action.ARCHIVE, null, "Ikea"));

        assertThat(rule.action()).isEqualTo(Action.MOVE_TO_FOLDER);
    }

    @Test
    void moveToFolderWithoutAFolderIsRejected() {
        assertThatThrownBy(() -> service.execute(
                new AddRuleCommand(RuleField.FROM_DOMAIN, "ikea.com", Action.MOVE_TO_FOLDER, null, " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("folder");
        assertThat(repository.findAll(GMX)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INBOX", "inbox", "/Ikea", "Ikea/", "Ik//ea", "Ikea*", "Ik%ea", "Ik\nea"})
    void unsafeFolderNamesAreRejected(String folder) {
        assertThatThrownBy(() -> service.execute(
                new AddRuleCommand(RuleField.FROM_DOMAIN, "ikea.com", null, null, folder)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nestedFoldersAreAllowed() {
        var rule = service.execute(new AddRuleCommand(RuleField.FROM_DOMAIN, "ikea.com", null, null, "Shops/Ikea"));

        assertThat(rule.folder()).isEqualTo("Shops/Ikea");
    }

    @Test
    void aRuleWithoutAFolderStillNeedsAnAction() {
        assertThatThrownBy(() -> service.execute(new AddRuleCommand(RuleField.FROM_DOMAIN, "ikea.com", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    @Test
    void aRuleWithoutAFolderDoesNotRouteToAFolder() {
        var rule = service.execute(new AddRuleCommand(RuleField.FROM_DOMAIN, "ikea.com", Action.ARCHIVE, null, null));

        assertThat(rule.folder()).isNull();
        assertThat(rule.apply(from("news@ikea.com")).orElseThrow().folder()).isNull();
    }

    @Test
    void everyChangeIsAnnouncedToTheClassifier() {
        var rule = service.execute(new AddRuleCommand(RuleField.FROM_DOMAIN, "ikea.com", null, null, "Ikea"));
        service.execute(rule.id());

        assertThat(changes).hasValue(2);
    }

    // -- per account ----------------------------------------------------------------

    @Test
    void rulesBelongToTheConnectedAccount() {
        var rule = service.execute(new AddRuleCommand(RuleField.FROM_DOMAIN, "ikea.com", null, null, "Ikea"));

        connections.save(new FakeMailProvider(GMAIL));

        assertThat(service.execute()).isEmpty();
        assertThat(service.rulesFor(GMX)).containsExactly(rule);
        assertThat(service.rulesFor(GMAIL)).isEmpty();
    }

    @Test
    void anotherAccountsRuleCannotBeDeleted() {
        var rule = service.execute(new AddRuleCommand(RuleField.FROM_DOMAIN, "ikea.com", null, null, "Ikea"));
        connections.save(new FakeMailProvider(GMAIL));

        assertThatThrownBy(() -> service.execute(rule.id())).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.rulesFor(GMX)).containsExactly(rule);
    }

    @Test
    void managingRulesNeedsAConnectedAccount() {
        var disconnected = new RuleService(repository, new InMemoryConnectionStore(), () -> {});

        assertThatThrownBy(disconnected::execute).isInstanceOf(NotConnectedException.class);
    }

    // -- rule actions ---------------------------------------------------------------

    @Test
    void aRuleCarriesItsActionIntoTheClassification() {
        var rule = service.execute(new AddRuleCommand(RuleField.FROM_ADDRESS, "research@finextra.com",
                Action.MOVE_TO_TRASH, null, null));

        var classification = rule.apply(from("Finextra <research@finextra.com>")).orElseThrow();

        assertThat(classification.action()).isEqualTo(Action.MOVE_TO_TRASH);
        assertThat(classification.folder()).isNull();
    }

    @Test
    void aFolderRuleCarriesItsFolderNotAnAction() {
        var rule = service.execute(new AddRuleCommand(RuleField.FROM_DOMAIN, "ikea.com", null, null, "Ikea"));

        var classification = rule.apply(from("news@ikea.com")).orElseThrow();

        assertThat(classification.action()).isNull();
        assertThat(classification.folder()).isEqualTo("Ikea");
    }
}
