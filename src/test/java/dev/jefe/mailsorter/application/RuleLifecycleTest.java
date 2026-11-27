package dev.jefe.mailsorter.application;

import dev.jefe.mailsorter.application.port.in.dto.AddRuleCommand;
import dev.jefe.mailsorter.application.port.in.dto.ClassifyCommand;
import dev.jefe.mailsorter.application.port.in.dto.Plan;
import dev.jefe.mailsorter.application.service.ClassifyService;
import dev.jefe.mailsorter.application.service.RuleService;
import dev.jefe.mailsorter.application.service.prefilter.PreFilteringClassifier;
import dev.jefe.mailsorter.application.service.prefilter.rules.RuleField;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.ClassificationSource;
import dev.jefe.mailsorter.domain.model.Email;
import dev.jefe.mailsorter.domain.policy.PolicyEngine;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryConnectionStore;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryPlanStore;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteDatabase;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteRuleStore;
import dev.jefe.mailsorter.testsupport.FakeMailProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adding and deleting a rule, wired the way CompositionRoot wires it: SQLite store, per-account prefilter,
 * classification cache. A deleted rule must be gone from the database and must stop deciding at once.
 */
class RuleLifecycleTest {

    private static final String GMX = "imap:imap.gmx.com|me@gmx.es";

    @TempDir Path dir;
    private Path dbFile;
    private RuleService rules;
    private ClassifyService classify;

    @BeforeEach
    void wire() {
        dbFile = dir.resolve("app.db");
        var db = new SqliteDatabase(dbFile);
        db.migrate();
        var repository = new SqliteRuleStore(db);

        var mailbox = new FakeMailProvider(GMX);
        mailbox.withEmails(new Email("1", "Finextra <research@finextra.com>", "Weekly research", "snippet", Map.of()));
        var connections = new InMemoryConnectionStore();
        connections.save(mailbox);

        var model = new Fakes.FakeClassifier().answer("1", Category.NEWSLETTER, 0.97);
        var prefilter = new PreFilteringClassifier(model, List.of(), repository::findAll);
        var policy = new PolicyEngine(Map.of(Category.NEWSLETTER, Action.ARCHIVE), 0.9);
        classify = new ClassifyService(connections, prefilter, policy, new InMemoryPlanStore());

        // same callback as CompositionRoot
        rules = new RuleService(repository, new dev.jefe.mailsorter.infra.persistence.memory.InMemoryFolderStore(),
                connections, () -> {
            prefilter.rulesChanged();
            classify.forgetCachedClassifications();
        });
    }

    private Plan.Entry classifyTheEmail() {
        return classify.execute(new ClassifyCommand(10)).entries().get(0);
    }

    private int rowsInDatabase() throws SQLException {
        try (var con = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             var rs = con.createStatement().executeQuery("SELECT COUNT(*) FROM prefilter_rules")) {
            return rs.getInt(1);
        }
    }

    @Test
    void aDeletedRuleIsGoneFromTheDatabaseAndStopsDecidingImmediately() throws SQLException {
        assertThat(classifyTheEmail().action()).isEqualTo(Action.ARCHIVE);           // the model alone

        var rule = rules.execute(new AddRuleCommand(RuleField.FROM_ADDRESS, "research@finextra.com",
                Action.MOVE_TO_TRASH, null, null));
        assertThat(rowsInDatabase()).isEqualTo(1);
        assertThat(classifyTheEmail().action()).isEqualTo(Action.MOVE_TO_TRASH);     // rule beats the cache

        rules.execute(rule.id());

        assertThat(rowsInDatabase()).isZero();
        assertThat(rules.execute()).isEmpty();
        var after = classifyTheEmail();
        assertThat(after.action()).isEqualTo(Action.ARCHIVE);                        // no stale rule, no stale cache
        assertThat(after.classification().source()).isEqualTo(ClassificationSource.MODEL);
    }
}
